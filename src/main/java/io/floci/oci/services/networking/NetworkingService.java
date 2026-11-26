package io.floci.oci.services.networking;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.NetworkReferences;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.common.net.Cidr4;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.networking.model.StoredNetworkResource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * OCI Core Virtual Network as state: VCNs, subnets, internet, NAT and service gateways, route
 * tables, security lists, network security groups and DHCP options. Nothing is wired to a real
 * Docker network. Shapes and rules follow oci-go-sdk/core; the defaults CreateVcn makes and the
 * fields Terraform reads back follow terraform-provider-oci/internal/service/core.
 *
 * <p>Every mutation runs under one lock, so a VCN and its children stay consistent (a subnet
 * cannot land in a VCN being deleted, two subnets cannot claim overlapping CIDRs).
 */
@ApplicationScoped
public class NetworkingService implements CompartmentUsage, NetworkReferences {

    private static final Logger LOG = Logger.getLogger(NetworkingService.class);

    static final String WR_SERVICE = "networking";
    static final String API_PREFIX = "/20160918";

    private static final DateTimeFormatter NAME_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    /** dnsLabel: letters and digits, starting with a letter, at most 15 characters (vcn.go, subnet.go). */
    private static final Pattern DNS_LABEL = Pattern.compile("^[A-Za-z][A-Za-z0-9]{0,14}$");
    private static final Set<String> RULE_TARGET_TYPES = Set.of("CIDR_BLOCK", "SERVICE_CIDR_BLOCK");
    private static final Set<String> NSG_ADDRESS_TYPES =
            Set.of("CIDR_BLOCK", "SERVICE_CIDR_BLOCK", "NETWORK_SECURITY_GROUP");

    private final StorageBackend<String, StoredNetworkResource> resources;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;
    private final Iterable<VcnLifecycleListener> vcnListeners;

    @Inject
    public NetworkingService(StorageFactory storageFactory, EmulatorConfig config,
                             ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                             OciContext ociContext, Instance<VcnLifecycleListener> vcnListeners) {
        this.config = config;
        this.ociContext = ociContext;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.vcnListeners = vcnListeners;
        this.resources = storageFactory.create("networking", "networking-resources.json",
                new TypeReference<Map<String, StoredNetworkResource>>() {});
    }

    NetworkingService(StorageBackend<String, StoredNetworkResource> resources, EmulatorConfig config,
                      WorkRequestService workRequests) {
        this(resources, config, workRequests, List.of());
    }

    NetworkingService(StorageBackend<String, StoredNetworkResource> resources, EmulatorConfig config,
                      WorkRequestService workRequests, Iterable<VcnLifecycleListener> vcnListeners) {
        this.resources = resources;
        this.config = config;
        this.ociContext = OciContext.fromConfig(config);
        this.serviceRegistry = null;
        this.workRequests = workRequests;
        this.vcnListeners = vcnListeners;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("networking")
                .enabled(config.services().networking().enabled())
                .storageKey("networking")
                .resourceClasses(NetworkingController.class)
                .build());
    }

    // ── Generic CRUD ───────────────────────────────────────────────────────────

    public synchronized StoredNetworkResource create(NetworkKind kind, Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String compartmentId = requireString(b, "compartmentId");
        StoredNetworkResource r = newResource(kind, compartmentId, str(b, "displayName"));
        r.setFreeformTags(stringMap(b, "freeformTags"));
        r.setDefinedTags(definedTags(b));
        if (kind == NetworkKind.VCN) {
            createVcn(r, b);
            VcnLifecycleListener.Vcn vcn = vcnOf(r);
            notifyVcnListeners(listener -> listener.vcnCreated(vcn), r.getId());
        } else {
            StoredNetworkResource vcn = requireVcnInBody(requireString(b, "vcnId"));
            r.setVcnId(vcn.getId());
            switch (kind) {
                case SUBNET -> initSubnet(r, vcn, b);
                case INTERNET_GATEWAY -> initInternetGateway(r, b);
                case NAT_GATEWAY -> initNatGateway(r, b);
                case SERVICE_GATEWAY -> initServiceGateway(r, b);
                case ROUTE_TABLE -> r.getAttributes().put("routeRules",
                        routeRules(requireList(b, "routeRules"), vcn.getId()));
                case SECURITY_LIST -> {
                    r.getAttributes().put("egressSecurityRules",
                            securityListRules(requireList(b, "egressSecurityRules"), "destination"));
                    r.getAttributes().put("ingressSecurityRules",
                            securityListRules(requireList(b, "ingressSecurityRules"), "source"));
                }
                case NETWORK_SECURITY_GROUP -> {
                    // rules arrive through the addSecurityRules action
                }
                case DHCP_OPTIONS -> initDhcpOptions(r, b);
                default -> throw new IllegalStateException("unhandled kind " + kind);
            }
            resources.put(r.getId(), r);
        }
        LOG.infov("create {0} {1} ({2})", kind.ocidType(), r.getDisplayName(), r.getId());
        return r;
    }

    public StoredNetworkResource get(NetworkKind kind, String id) {
        return resources.get(id)
                .filter(r -> r.getKind() == kind)
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: " + kind.ocidType() + " " + id));
    }

    /**
     * A copy of the stored resource to change. Writes go through {@code resources.put} only after
     * every check has passed, so a rejected request leaves the stored resource as it was.
     */
    private StoredNetworkResource editable(NetworkKind kind, String id) {
        return get(kind, id).copy();
    }

    public List<StoredNetworkResource> list(NetworkKind kind, String compartmentId, String vcnId,
                                            String displayName, String lifecycleState,
                                            String sortBy, String sortOrder) {
        Comparator<StoredNetworkResource> order;
        boolean byName = "DISPLAYNAME".equals(sortBy);
        if (byName) {
            order = Comparator.comparing(r -> Objects.toString(r.getDisplayName(), ""),
                    String.CASE_INSENSITIVE_ORDER);
        } else {
            order = Comparator.comparing(StoredNetworkResource::getTimeCreated);
        }
        // TIMECREATED sorts descending and DISPLAYNAME ascending unless sortOrder says otherwise.
        boolean descending = sortOrder == null ? !byName : "DESC".equals(sortOrder);
        if (descending) {
            order = order.reversed();
        }
        return resources.scan(k -> true).stream()
                .filter(r -> r.getKind() == kind)
                .filter(r -> compartmentId == null || compartmentId.equals(r.getCompartmentId()))
                .filter(r -> vcnId == null || vcnId.equals(r.getVcnId()))
                .filter(r -> displayName == null || displayName.equals(r.getDisplayName()))
                .filter(r -> lifecycleState == null || lifecycleState.equals(r.getLifecycleState()))
                .sorted(order)
                .toList();
    }

    public synchronized StoredNetworkResource update(NetworkKind kind, String id, Map<String, Object> body,
                                                     String ifMatch) {
        StoredNetworkResource r = editable(kind, id);
        Etags.checkIfMatch(ifMatch, r.getEtag());
        Map<String, Object> b = body == null ? Map.of() : body;
        String displayName = str(b, "displayName");
        if (displayName != null) {
            r.setDisplayName(displayName);
        }
        if (b.get("freeformTags") instanceof Map<?, ?>) {
            r.setFreeformTags(stringMap(b, "freeformTags"));
        }
        if (b.get("definedTags") instanceof Map<?, ?>) {
            r.setDefinedTags(definedTags(b));
        }
        Map<String, Object> a = r.getAttributes();
        switch (kind) {
            case VCN -> {
                // UpdateVcnDetails carries only names and tags
            }
            case SUBNET -> updateSubnet(r, b);
            case INTERNET_GATEWAY -> {
                if (b.get("isEnabled") instanceof Boolean enabled) {
                    a.put("isEnabled", enabled);
                }
                putRouteTableRef(r, b);
            }
            case NAT_GATEWAY -> {
                if (b.get("blockTraffic") instanceof Boolean block) {
                    a.put("blockTraffic", block);
                }
                putRouteTableRef(r, b);
            }
            case SERVICE_GATEWAY -> {
                if (b.get("blockTraffic") instanceof Boolean block) {
                    a.put("blockTraffic", block);
                }
                if (b.get("services") instanceof List<?> services) {
                    a.put("services", serviceRefs(services));
                }
                putRouteTableRef(r, b);
            }
            case ROUTE_TABLE -> {
                if (b.get("routeRules") instanceof List<?> rules) {
                    a.put("routeRules", routeRules(rules, r.getVcnId()));
                }
            }
            case SECURITY_LIST -> {
                if (b.get("egressSecurityRules") instanceof List<?> rules) {
                    a.put("egressSecurityRules", securityListRules(rules, "destination"));
                }
                if (b.get("ingressSecurityRules") instanceof List<?> rules) {
                    a.put("ingressSecurityRules", securityListRules(rules, "source"));
                }
            }
            case NETWORK_SECURITY_GROUP -> {
                // UpdateNetworkSecurityGroupDetails carries only names and tags
            }
            case DHCP_OPTIONS -> {
                if (b.get("options") instanceof List<?> options) {
                    a.put("options", dhcpOptions(options));
                }
                String domainNameType = str(b, "domainNameType");
                if (domainNameType != null) {
                    a.put("domainNameType", domainNameType);
                }
            }
            default -> throw new IllegalStateException("unhandled kind " + kind);
        }
        r.setEtag(Etags.newEtag());
        resources.put(id, r);
        return r;
    }

    public synchronized void delete(NetworkKind kind, String id, String ifMatch) {
        StoredNetworkResource r = get(kind, id);
        Etags.checkIfMatch(ifMatch, r.getEtag());
        if (r.isVcnDefault()) {
            throw OciException.conflict("The default " + kind.ocidType() + " of a VCN cannot be deleted: " + id);
        }
        switch (kind) {
            case VCN -> deleteVcn(r);
            case ROUTE_TABLE -> requireUnreferenced(r, "routeTableId");
            case DHCP_OPTIONS -> requireUnreferenced(r, "dhcpOptionsId");
            case SECURITY_LIST -> {
                boolean used = children(r.getVcnId(), NetworkKind.SUBNET).stream()
                        .anyMatch(s -> stringList(s.getAttributes().get("securityListIds")).contains(id));
                if (used) {
                    throw OciException.conflict("Security list " + id + " is still used by a subnet.");
                }
            }
            case INTERNET_GATEWAY, NAT_GATEWAY, SERVICE_GATEWAY -> requireNotRouteTarget(r);
            case SUBNET -> {
                if (!vnicsOn(id).isEmpty()) {
                    throw OciException.conflict("Subnet " + id + " still has attached VNICs.");
                }
            }
            case NETWORK_SECURITY_GROUP -> {
                if (!vnicsInNsg(id).isEmpty()) {
                    throw OciException.conflict("Network security group " + id + " still contains VNICs.");
                }
            }
            default -> {
                // the remaining kinds have no dependants floci-oci tracks
            }
        }
        if (kind != NetworkKind.VCN) {
            resources.delete(id);
        }
        LOG.infov("delete {0} {1}", kind.ocidType(), id);
    }

    public record CompartmentMove(StoredNetworkResource resource, String workRequestId) {
    }

    public synchronized CompartmentMove changeCompartment(NetworkKind kind, String id, String compartmentId,
                                                          String ifMatch) {
        StoredNetworkResource r = editable(kind, id);
        Etags.checkIfMatch(ifMatch, r.getEtag());
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        r.setCompartmentId(compartmentId);
        r.setEtag(Etags.newEtag());
        resources.put(id, r);
        String workRequestId = null;
        if (kind.compartmentMoveIsWorkRequest()) {
            workRequestId = workRequests.succeeded(WR_SERVICE,
                    "CHANGE_" + kind.name() + "_COMPARTMENT", compartmentId,
                    List.of(WorkRequestService.resource(kind.ocidType(), "UPDATED", id,
                            API_PREFIX + "/" + kind.collection() + "/" + id)));
        }
        return new CompartmentMove(r, workRequestId);
    }

    // ── Service gateways: attach and detach ────────────────────────────────────

    public synchronized StoredNetworkResource attachService(String serviceGatewayId, String serviceId,
                                                            boolean attach, String ifMatch) {
        StoredNetworkResource r = editable(NetworkKind.SERVICE_GATEWAY, serviceGatewayId);
        Etags.checkIfMatch(ifMatch, r.getEtag());
        Map<String, Object> service = serviceRef(serviceId);
        List<Map<String, Object>> current = new ArrayList<>(mapList(r.getAttributes().get("services")));
        current.removeIf(s -> serviceId.equals(s.get("serviceId")));
        if (attach) {
            current.add(service);
        }
        r.getAttributes().put("services", current);
        r.setEtag(Etags.newEtag());
        resources.put(serviceGatewayId, r);
        return r;
    }

    /**
     * The Oracle Services Network entries a service gateway can reach, per region:
     * {@code all-<code>-services-in-oracle-services-network} and {@code oci-<code>-objectstorage}
     * (oci-go-sdk/core/service.go; names as Terraform's examples filter them). Their OCIDs are
     * derived from the name, so they are stable across restarts and Terraform state stays valid.
     */
    public List<Map<String, Object>> listServices() {
        String code = ociContext.regionCode().toLowerCase(Locale.ROOT);
        String upper = code.toUpperCase(Locale.ROOT);
        return List.of(
                service("all-" + code + "-services-in-oracle-services-network",
                        "All " + upper + " Services In Oracle Services Network",
                        "All " + upper + " Services In Oracle Services Network"),
                service("oci-" + code + "-objectstorage", "OCI " + upper + " Object Storage",
                        "OCI " + upper + " Object Storage"));
    }

    public Map<String, Object> getService(String serviceId) {
        return listServices().stream()
                .filter(s -> serviceId.equals(s.get("id")))
                .findFirst()
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: service " + serviceId));
    }

    // ── Network security group rules ───────────────────────────────────────────

    public synchronized List<Map<String, Object>> addSecurityRules(String nsgId, List<?> rules) {
        StoredNetworkResource nsg = editable(NetworkKind.NETWORK_SECURITY_GROUP, nsgId);
        List<Map<String, Object>> added = new ArrayList<>();
        for (Object raw : rules == null ? List.of() : rules) {
            Map<String, Object> rule = nsgRule(asMap(raw, "securityRules"), nsg.getVcnId());
            rule.put("id", newRuleId(nsg));
            rule.put("isValid", true);
            rule.put("timeCreated", Instant.now().toString());
            // Added at once, so newRuleId sees the IDs this batch already took. nsg is a copy, saved
            // only after every rule passes.
            nsg.getSecurityRules().add(rule);
            added.add(rule);
        }
        nsg.setEtag(Etags.newEtag());
        resources.put(nsgId, nsg);
        return added;
    }

    public synchronized List<Map<String, Object>> updateSecurityRules(String nsgId, List<?> rules) {
        StoredNetworkResource nsg = editable(NetworkKind.NETWORK_SECURITY_GROUP, nsgId);
        List<Map<String, Object>> updated = new ArrayList<>();
        for (Object raw : rules == null ? List.of() : rules) {
            Map<String, Object> input = asMap(raw, "securityRules");
            String ruleId = requireString(input, "id");
            int index = indexOfRule(nsg, ruleId);
            Map<String, Object> existing = nsg.getSecurityRules().get(index);
            Map<String, Object> rule = nsgRule(input, nsg.getVcnId());
            rule.put("id", ruleId);
            rule.put("isValid", true);
            rule.put("timeCreated", existing.get("timeCreated"));
            nsg.getSecurityRules().set(index, rule);
            updated.add(rule);
        }
        nsg.setEtag(Etags.newEtag());
        resources.put(nsgId, nsg);
        return updated;
    }

    public synchronized void removeSecurityRules(String nsgId, List<String> ruleIds) {
        StoredNetworkResource nsg = editable(NetworkKind.NETWORK_SECURITY_GROUP, nsgId);
        for (String ruleId : ruleIds == null ? List.<String>of() : ruleIds) {
            nsg.getSecurityRules().remove(indexOfRule(nsg, ruleId));
        }
        nsg.setEtag(Etags.newEtag());
        resources.put(nsgId, nsg);
    }

    public List<Map<String, Object>> listSecurityRules(String nsgId, String direction) {
        StoredNetworkResource nsg = get(NetworkKind.NETWORK_SECURITY_GROUP, nsgId);
        return nsg.getSecurityRules().stream()
                .filter(rule -> direction == null || direction.equals(rule.get("direction")))
                .toList();
    }

    // ── VCN ────────────────────────────────────────────────────────────────────

    private void createVcn(StoredNetworkResource vcn, Map<String, Object> b) {
        List<String> cidrBlocks = new ArrayList<>(stringList(b.get("cidrBlocks")));
        String cidrBlock = str(b, "cidrBlock");
        if (cidrBlocks.isEmpty() && cidrBlock != null) {
            cidrBlocks.add(cidrBlock);
        }
        if (cidrBlocks.isEmpty()) {
            throw OciException.invalidParameter("cidrBlock or cidrBlocks is required");
        }
        List<Cidr4> parsed = new ArrayList<>();
        for (String block : cidrBlocks) {
            Cidr4 cidr = parseCidr(block);
            if (cidr.prefix() < 16 || cidr.prefix() > 30) {
                throw OciException.invalidParameter(
                        "VCN CIDR block " + block + " must have a prefix between /16 and /30");
            }
            for (Cidr4 other : parsed) {
                if (other.overlaps(cidr)) {
                    throw OciException.invalidParameter("VCN CIDR blocks " + other + " and " + cidr + " overlap");
                }
            }
            parsed.add(cidr);
        }
        String dnsLabel = dnsLabel(b);
        Map<String, Object> a = vcn.getAttributes();
        a.put("cidrBlock", cidrBlocks.get(0));
        a.put("cidrBlocks", cidrBlocks);
        if (dnsLabel != null) {
            a.put("dnsLabel", dnsLabel);
            a.put("vcnDomainName", dnsLabel.toLowerCase(Locale.ROOT) + ".oraclevcn.com");
        }
        a.put("ipv6CidrBlocks", List.of());
        a.put("ipv6PrivateCidrBlocks", List.of());
        a.put("byoipv6CidrBlocks", List.of());

        String name = vcn.getDisplayName();
        StoredNetworkResource routeTable = vcnDefault(vcn, NetworkKind.ROUTE_TABLE, "Default Route Table for " + name);
        routeTable.getAttributes().put("routeRules", new ArrayList<>());

        StoredNetworkResource securityList =
                vcnDefault(vcn, NetworkKind.SECURITY_LIST, "Default Security List for " + name);
        securityList.getAttributes().put("egressSecurityRules", List.of(egressAllRule()));
        securityList.getAttributes().put("ingressSecurityRules", defaultIngressRules(cidrBlocks.get(0)));

        StoredNetworkResource dhcp = vcnDefault(vcn, NetworkKind.DHCP_OPTIONS, "Default DHCP Options for " + name);
        List<Map<String, Object>> options = new ArrayList<>();
        Map<String, Object> dns = new LinkedHashMap<>();
        dns.put("type", "DomainNameServer");
        dns.put("customDnsServers", List.of());
        dns.put("serverType", "VcnLocalPlusInternet");
        options.add(dns);
        if (dnsLabel != null) {
            Map<String, Object> search = new LinkedHashMap<>();
            search.put("type", "SearchDomain");
            search.put("searchDomainNames", List.of(a.get("vcnDomainName")));
            options.add(search);
        }
        dhcp.getAttributes().put("options", options);
        dhcp.getAttributes().put("domainNameType", "CUSTOM_DOMAIN");

        a.put("defaultRouteTableId", routeTable.getId());
        a.put("defaultSecurityListId", securityList.getId());
        a.put("defaultDhcpOptionsId", dhcp.getId());
        resources.put(vcn.getId(), vcn);
        resources.put(routeTable.getId(), routeTable);
        resources.put(securityList.getId(), securityList);
        resources.put(dhcp.getId(), dhcp);
    }

    private StoredNetworkResource vcnDefault(StoredNetworkResource vcn, NetworkKind kind, String name) {
        StoredNetworkResource r = newResource(kind, vcn.getCompartmentId(), name);
        r.setVcnId(vcn.getId());
        r.setVcnDefault(true);
        return r;
    }

    /**
     * Real OCI's default security list, as dumped from a live tenancy
     * (oci-python-sdk/examples/showoci/README.md:681-685): SSH and ICMP 3/4 from anywhere, ICMP 3
     * from inside the VCN, and all egress.
     */
    private static List<Map<String, Object>> defaultIngressRules(String vcnCidr) {
        Map<String, Object> ssh = securityListRule("6", "source", "0.0.0.0/0");
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("max", 22);
        range.put("min", 22);
        Map<String, Object> tcp = new LinkedHashMap<>();
        tcp.put("destinationPortRange", range);
        ssh.put("tcpOptions", tcp);

        Map<String, Object> fragmentation = securityListRule("1", "source", "0.0.0.0/0");
        Map<String, Object> icmp34 = new LinkedHashMap<>();
        icmp34.put("code", 4);
        icmp34.put("type", 3);
        fragmentation.put("icmpOptions", icmp34);

        Map<String, Object> unreachable = securityListRule("1", "source", vcnCidr);
        Map<String, Object> icmp3 = new LinkedHashMap<>();
        icmp3.put("type", 3);
        unreachable.put("icmpOptions", icmp3);
        return List.of(ssh, fragmentation, unreachable);
    }

    private static Map<String, Object> egressAllRule() {
        return securityListRule("all", "destination", "0.0.0.0/0");
    }

    private static Map<String, Object> securityListRule(String protocol, String addressField, String address) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put(addressField, address);
        rule.put(addressField + "Type", "CIDR_BLOCK");
        rule.put("isStateless", false);
        rule.put("protocol", protocol);
        return rule;
    }

    private void deleteVcn(StoredNetworkResource vcn) {
        List<StoredNetworkResource> owned = resources.scan(k -> true).stream()
                .filter(r -> vcn.getId().equals(r.getVcnId()))
                .toList();
        List<String> blocking = owned.stream()
                .filter(r -> !r.isVcnDefault())
                .map(r -> r.getKind().ocidType() + " " + r.getId())
                .toList();
        if (!blocking.isEmpty()) {
            throw OciException.conflict("The VCN " + vcn.getId()
                    + " must be empty and have no attached gateways before it is deleted; it still has "
                    + String.join(", ", blocking));
        }
        owned.forEach(r -> resources.delete(r.getId()));
        resources.delete(vcn.getId());
        notifyVcnListeners(listener -> listener.vcnDeleted(vcn.getId()), vcn.getId());
    }

    /**
     * The DNS resolver attached to a VCN (GetVcnDnsResolverAssociation), from the first listener that has one.
     * It holds the Networking lock so a concurrent VCN delete cannot run between the lookup and a listener
     * creating the resolver.
     */
    public synchronized Optional<String> dnsResolverId(String vcnId) {
        VcnLifecycleListener.Vcn vcn = vcnOf(get(NetworkKind.VCN, vcnId));
        for (VcnLifecycleListener listener : vcnListeners) {
            Optional<String> resolverId = listener.dnsResolverId(vcn);
            if (resolverId.isPresent()) {
                return resolverId;
            }
        }
        return Optional.empty();
    }

    private static VcnLifecycleListener.Vcn vcnOf(StoredNetworkResource vcn) {
        return new VcnLifecycleListener.Vcn(vcn.getId(), vcn.getCompartmentId(), vcn.getDisplayName());
    }

    private void notifyVcnListeners(Consumer<VcnLifecycleListener> event, String vcnId) {
        for (VcnLifecycleListener listener : vcnListeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                LOG.warnv("VCN listener {0} failed for {1}: {2}", listener.getClass().getSimpleName(), vcnId,
                        e.getMessage());
            }
        }
    }

    // ── Subnet ─────────────────────────────────────────────────────────────────

    private void initSubnet(StoredNetworkResource subnet, StoredNetworkResource vcn, Map<String, Object> b) {
        String cidrBlock = str(b, "cidrBlock");
        List<String> ipv4 = stringList(b.get("ipv4CidrBlocks"));
        if (cidrBlock == null && !ipv4.isEmpty()) {
            cidrBlock = ipv4.get(0);
        }
        if (cidrBlock == null) {
            throw OciException.invalidParameter("cidrBlock is required");
        }
        Cidr4 cidr = parseCidr(cidrBlock);
        if (cidr.prefix() > 30) {
            throw OciException.invalidParameter("Subnet CIDR block " + cidrBlock + " must be /30 or larger");
        }
        boolean inVcn = stringList(vcn.getAttributes().get("cidrBlocks")).stream()
                .map(this::parseCidr)
                .anyMatch(v -> v.contains(cidr));
        if (!inVcn) {
            throw OciException.invalidParameter(
                    "Subnet CIDR block " + cidrBlock + " is not within the CIDR blocks of VCN " + vcn.getId());
        }
        for (StoredNetworkResource sibling : children(vcn.getId(), NetworkKind.SUBNET)) {
            Cidr4 other = parseCidr((String) sibling.getAttributes().get("cidrBlock"));
            if (other.overlaps(cidr)) {
                throw OciException.invalidParameter("Subnet CIDR block " + cidrBlock
                        + " overlaps subnet " + sibling.getId() + " (" + other + ")");
            }
        }
        String dnsLabel = dnsLabel(b);
        if (dnsLabel != null) {
            boolean taken = children(vcn.getId(), NetworkKind.SUBNET).stream()
                    .anyMatch(s -> dnsLabel.equalsIgnoreCase((String) s.getAttributes().get("dnsLabel")));
            if (taken) {
                throw OciException.invalidParameter(
                        "dnsLabel " + dnsLabel + " is already used by another subnet in VCN " + vcn.getId());
            }
        }
        Map<String, Object> a = subnet.getAttributes();
        String availabilityDomain = str(b, "availabilityDomain");
        if (availabilityDomain != null) {
            a.put("availabilityDomain", availabilityDomain);
        }
        a.put("cidrBlock", cidrBlock);
        a.put("ipv4CidrBlocks", List.of(cidrBlock));
        a.put("ipv6CidrBlocks", List.of());
        if (dnsLabel != null) {
            a.put("dnsLabel", dnsLabel);
            Object vcnLabel = vcn.getAttributes().get("dnsLabel");
            if (vcnLabel instanceof String label) {
                a.put("subnetDomainName", (dnsLabel + "." + label).toLowerCase(Locale.ROOT) + ".oraclevcn.com");
            }
        }
        a.put("routeTableId", ownedRef(b, "routeTableId", NetworkKind.ROUTE_TABLE, vcn,
                (String) vcn.getAttributes().get("defaultRouteTableId")));
        a.put("dhcpOptionsId", ownedRef(b, "dhcpOptionsId", NetworkKind.DHCP_OPTIONS, vcn,
                (String) vcn.getAttributes().get("defaultDhcpOptionsId")));
        a.put("securityListIds", securityListRefs(b, vcn));
        // prohibitInternetIngress and prohibitPublicIpOnVnic are the same setting under two names.
        Boolean ingress = b.get("prohibitInternetIngress") instanceof Boolean v ? v : null;
        Boolean publicIp = b.get("prohibitPublicIpOnVnic") instanceof Boolean v ? v : null;
        boolean prohibit = Objects.requireNonNullElse(ingress, Objects.requireNonNullElse(publicIp, false));
        a.put("prohibitInternetIngress", prohibit);
        a.put("prohibitPublicIpOnVnic", prohibit);
        a.put("virtualRouterIp", cidr.addressAt(1).orElseThrow());
        a.put("virtualRouterMac", syntheticMac());
    }

    private void updateSubnet(StoredNetworkResource subnet, Map<String, Object> b) {
        StoredNetworkResource vcn = get(NetworkKind.VCN, subnet.getVcnId());
        Map<String, Object> a = subnet.getAttributes();
        String cidrBlock = str(b, "cidrBlock");
        if (cidrBlock != null && !cidrBlock.equals(a.get("cidrBlock"))) {
            throw OciException.invalidParameter("Changing a subnet CIDR block is not supported by floci-oci");
        }
        if (str(b, "routeTableId") != null) {
            a.put("routeTableId", ownedRef(b, "routeTableId", NetworkKind.ROUTE_TABLE, vcn, null));
        }
        if (str(b, "dhcpOptionsId") != null) {
            a.put("dhcpOptionsId", ownedRef(b, "dhcpOptionsId", NetworkKind.DHCP_OPTIONS, vcn, null));
        }
        if (b.get("securityListIds") instanceof List<?>) {
            a.put("securityListIds", securityListRefs(b, vcn));
        }
    }

    private List<String> securityListRefs(Map<String, Object> b, StoredNetworkResource vcn) {
        if (!(b.get("securityListIds") instanceof List<?>)) {
            return List.of((String) vcn.getAttributes().get("defaultSecurityListId"));
        }
        List<String> ids = stringList(b.get("securityListIds"));
        for (String id : ids) {
            requireOwned(id, NetworkKind.SECURITY_LIST, vcn, "securityListIds");
        }
        return ids;
    }

    // ── Gateways ───────────────────────────────────────────────────────────────

    private void initInternetGateway(StoredNetworkResource r, Map<String, Object> b) {
        if (!(b.get("isEnabled") instanceof Boolean enabled)) {
            throw OciException.invalidParameter("isEnabled is required");
        }
        r.getAttributes().put("isEnabled", enabled);
        putRouteTableRef(r, b);
    }

    private void initNatGateway(StoredNetworkResource r, Map<String, Object> b) {
        Map<String, Object> a = r.getAttributes();
        a.put("blockTraffic", b.get("blockTraffic") instanceof Boolean block && block);
        a.put("natIp", syntheticPublicIp(r.getId()));
        String publicIpId = str(b, "publicIpId");
        if (publicIpId != null) {
            a.put("publicIpId", publicIpId);
        }
        putRouteTableRef(r, b);
    }

    private void initServiceGateway(StoredNetworkResource r, Map<String, Object> b) {
        Map<String, Object> a = r.getAttributes();
        a.put("blockTraffic", b.get("blockTraffic") instanceof Boolean block && block);
        a.put("services", serviceRefs(requireList(b, "services")));
        putRouteTableRef(r, b);
    }

    private List<Map<String, Object>> serviceRefs(List<?> services) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Object raw : services) {
            refs.add(serviceRef(requireString(asMap(raw, "services"), "serviceId")));
        }
        return refs;
    }

    private Map<String, Object> serviceRef(String serviceId) {
        Map<String, Object> service = listServices().stream()
                .filter(s -> serviceId.equals(s.get("id")))
                .findFirst()
                .orElseThrow(() -> OciException.relatedResourceNotAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: service " + serviceId));
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("serviceId", serviceId);
        ref.put("serviceName", service.get("name"));
        return ref;
    }

    private void putRouteTableRef(StoredNetworkResource gateway, Map<String, Object> b) {
        if (str(b, "routeTableId") != null) {
            StoredNetworkResource vcn = get(NetworkKind.VCN, gateway.getVcnId());
            gateway.getAttributes().put("routeTableId",
                    ownedRef(b, "routeTableId", NetworkKind.ROUTE_TABLE, vcn, null));
        }
    }

    private void requireNotRouteTarget(StoredNetworkResource gateway) {
        boolean targeted = children(gateway.getVcnId(), NetworkKind.ROUTE_TABLE).stream()
                .flatMap(rt -> mapList(rt.getAttributes().get("routeRules")).stream())
                .anyMatch(rule -> gateway.getId().equals(rule.get("networkEntityId")));
        if (targeted) {
            throw OciException.conflict("Gateway " + gateway.getId() + " is the target of a route rule.");
        }
    }

    // ── Route rules, security rules, DHCP options ──────────────────────────────

    private List<Map<String, Object>> routeRules(List<?> rules, String vcnId) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object raw : rules) {
            Map<String, Object> input = asMap(raw, "routeRules");
            String target = requireString(input, "networkEntityId");
            requireRouteTarget(target, vcnId);
            String cidrBlock = str(input, "cidrBlock");
            String destination = str(input, "destination");
            if (cidrBlock != null && destination != null && !cidrBlock.equals(destination)) {
                throw OciException.invalidParameter("A route rule cannot set both cidrBlock and destination");
            }
            String address = destination != null ? destination : cidrBlock;
            if (address == null) {
                throw OciException.invalidParameter("A route rule needs a destination");
            }
            String destinationType = Objects.requireNonNullElse(str(input, "destinationType"), "CIDR_BLOCK");
            requireOneOf(destinationType, RULE_TARGET_TYPES, "destinationType");
            if ("CIDR_BLOCK".equals(destinationType)) {
                parseCidr(address);
            }
            // The service returns both cidrBlock and destination (core_route_table_resource.go).
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("cidrBlock", address);
            rule.put("destination", address);
            rule.put("destinationType", destinationType);
            rule.put("networkEntityId", target);
            String description = str(input, "description");
            if (description != null) {
                rule.put("description", description);
            }
            rule.put("routeType", Objects.requireNonNullElse(str(input, "routeType"), "STATIC"));
            normalized.add(rule);
        }
        return normalized;
    }

    /** Gateways floci-oci serves must exist in the same VCN; other targets (DRGs, private IPs) are not checked. */
    private void requireRouteTarget(String targetId, String vcnId) {
        for (NetworkKind kind : List.of(NetworkKind.INTERNET_GATEWAY, NetworkKind.NAT_GATEWAY,
                NetworkKind.SERVICE_GATEWAY)) {
            if (Ocids.isOfType(targetId, kind.ocidType())) {
                StoredNetworkResource target = resources.get(targetId).filter(r -> r.getKind() == kind)
                        .orElseThrow(() -> OciException.relatedResourceNotAuthorizedOrNotFound(
                                "Authorization failed or requested resource not found: " + targetId));
                if (!vcnId.equals(target.getVcnId())) {
                    throw OciException.invalidParameter("Route target " + targetId + " belongs to another VCN");
                }
            }
        }
    }

    private List<Map<String, Object>> securityListRules(List<?> rules, String addressField) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object raw : rules) {
            Map<String, Object> input = asMap(raw, addressField.equals("source")
                    ? "ingressSecurityRules" : "egressSecurityRules");
            Map<String, Object> rule = new LinkedHashMap<>();
            String description = str(input, "description");
            if (description != null) {
                rule.put("description", description);
            }
            rule.put(addressField, requireString(input, addressField));
            String type = Objects.requireNonNullElse(str(input, addressField + "Type"), "CIDR_BLOCK");
            requireOneOf(type, RULE_TARGET_TYPES, addressField + "Type");
            rule.put(addressField + "Type", type);
            copyIfPresent(input, rule, "icmpOptions");
            rule.put("isStateless", input.get("isStateless") instanceof Boolean s && s);
            rule.put("protocol", requireString(input, "protocol"));
            copyIfPresent(input, rule, "tcpOptions");
            copyIfPresent(input, rule, "udpOptions");
            normalized.add(rule);
        }
        return normalized;
    }

    private Map<String, Object> nsgRule(Map<String, Object> input, String vcnId) {
        String direction = requireString(input, "direction");
        requireOneOf(direction, Set.of("INGRESS", "EGRESS"), "direction");
        String addressField = "INGRESS".equals(direction) ? "source" : "destination";
        Map<String, Object> rule = new LinkedHashMap<>();
        String description = str(input, "description");
        if (description != null) {
            rule.put("description", description);
        }
        rule.put("direction", direction);
        String address = str(input, addressField);
        if (address != null) {
            String type = Objects.requireNonNullElse(str(input, addressField + "Type"), "CIDR_BLOCK");
            requireOneOf(type, NSG_ADDRESS_TYPES, addressField + "Type");
            if ("NETWORK_SECURITY_GROUP".equals(type)) {
                StoredNetworkResource peer = resources.get(address)
                        .filter(r -> r.getKind() == NetworkKind.NETWORK_SECURITY_GROUP)
                        .orElseThrow(() -> OciException.relatedResourceNotAuthorizedOrNotFound(
                                "Authorization failed or requested resource not found: " + address));
                if (!vcnId.equals(peer.getVcnId())) {
                    throw OciException.invalidParameter("Network security group " + address + " belongs to another VCN");
                }
            }
            rule.put(addressField, address);
            rule.put(addressField + "Type", type);
        }
        copyIfPresent(input, rule, "icmpOptions");
        rule.put("isStateless", input.get("isStateless") instanceof Boolean s && s);
        rule.put("protocol", requireString(input, "protocol"));
        copyIfPresent(input, rule, "tcpOptions");
        copyIfPresent(input, rule, "udpOptions");
        return rule;
    }

    private static String newRuleId(StoredNetworkResource nsg) {
        while (true) {
            String id = HexFormat.of().withUpperCase().formatHex(new byte[]{
                    (byte) ThreadLocalRandom.current().nextInt(256),
                    (byte) ThreadLocalRandom.current().nextInt(256),
                    (byte) ThreadLocalRandom.current().nextInt(256)});
            if (nsg.getSecurityRules().stream().noneMatch(r -> id.equals(r.get("id")))) {
                return id;
            }
        }
    }

    private static int indexOfRule(StoredNetworkResource nsg, String ruleId) {
        for (int i = 0; i < nsg.getSecurityRules().size(); i++) {
            if (ruleId.equals(nsg.getSecurityRules().get(i).get("id"))) {
                return i;
            }
        }
        throw OciException.invalidParameter("Security rule " + ruleId + " does not exist in " + nsg.getId());
    }

    private void initDhcpOptions(StoredNetworkResource r, Map<String, Object> b) {
        r.getAttributes().put("options", dhcpOptions(requireList(b, "options")));
        String domainNameType = str(b, "domainNameType");
        if (domainNameType != null) {
            requireOneOf(domainNameType, Set.of("SUBNET_DOMAIN", "VCN_DOMAIN", "CUSTOM_DOMAIN"), "domainNameType");
            r.getAttributes().put("domainNameType", domainNameType);
        }
    }

    private static List<Map<String, Object>> dhcpOptions(List<?> options) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object raw : options) {
            Map<String, Object> input = asMap(raw, "options");
            String type = requireString(input, "type");
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("type", type);
            switch (type) {
                case "DomainNameServer" -> {
                    String serverType = requireString(input, "serverType");
                    requireOneOf(serverType, Set.of("VcnLocal", "VcnLocalPlusInternet", "CustomDnsServer"),
                            "serverType");
                    List<String> servers = stringList(input.get("customDnsServers"));
                    if ("CustomDnsServer".equals(serverType) && servers.isEmpty()) {
                        throw OciException.invalidParameter("customDnsServers is required for CustomDnsServer");
                    }
                    option.put("customDnsServers", servers);
                    option.put("serverType", serverType);
                }
                case "SearchDomain" -> {
                    List<String> names = stringList(input.get("searchDomainNames"));
                    if (names.isEmpty()) {
                        throw OciException.invalidParameter("searchDomainNames is required for SearchDomain");
                    }
                    option.put("searchDomainNames", names);
                }
                default -> throw OciException.invalidParameter("Unknown DHCP option type: " + type);
            }
            normalized.add(option);
        }
        return normalized;
    }

    // ── References between resources ───────────────────────────────────────────

    @Override
    public void requireVcn(String vcnId) {
        if (vcnId != null && checksReferences()) {
            requireVcnInBody(vcnId);
        }
    }

    @Override
    public void requireSubnets(Collection<String> subnetIds) {
        requireAll(subnetIds, NetworkKind.SUBNET);
    }

    @Override
    public void requireNsgs(Collection<String> nsgIds) {
        requireAll(nsgIds, NetworkKind.NETWORK_SECURITY_GROUP);
    }

    @Override
    public void requireSubnetsInVcn(Collection<String> subnetIds, String vcnId, String field) {
        requireSubnets(subnetIds);
        if (subnetIds == null || vcnId == null || !checksReferences()) {
            return;
        }
        StoredNetworkResource vcn = requireVcnInBody(vcnId);
        for (String id : subnetIds) {
            requireOwned(id, NetworkKind.SUBNET, vcn, field);
        }
    }

    // ── VNICs ──────────────────────────────────────────────────────────────────

    /**
     * A VNIC on a subnet, the shape of oci-go-sdk/core/vnic.go. The private IP is the requested
     * one, or the next free address of the subnet (OCI keeps the first two and the last address
     * of every subnet: network, virtual router and broadcast, which a requested address cannot be
     * either), or unset until the workload reports the one it got. Each NSG must be in the subnet's
     * VCN. A public IP, when asked for, comes from 203.0.113.0/24 (RFC 5737), since no
     * address floci-oci hands out is publicly routable.
     */
    @Override
    public synchronized String attachVnic(VnicRequest request) {
        if (!checksReferences()) {
            return Ocids.generate(NetworkKind.VNIC.ocidType(), ociContext.realm(), ociContext.regionCode());
        }
        if (request.subnetId() == null) {
            throw OciException.missingParameter("subnetId is required");
        }
        requireSubnets(List.of(request.subnetId()));
        requireNsgs(request.nsgIds());
        StoredNetworkResource subnet = get(NetworkKind.SUBNET, request.subnetId());
        requireNsgsInVcn(request.nsgIds(), subnet.getVcnId());
        StoredNetworkResource vnic = newResource(NetworkKind.VNIC, request.compartmentId(), request.displayName());
        vnic.setFreeformTags(request.freeformTags() != null ? request.freeformTags() : Map.of());
        vnic.setDefinedTags(request.definedTags() != null ? request.definedTags() : Map.of());
        Map<String, Object> a = vnic.getAttributes();
        Object subnetDomain = subnet.getAttributes().get("availabilityDomain");
        a.put("availabilityDomain", request.availabilityDomain() != null ? request.availabilityDomain() : subnetDomain);
        a.put("subnetId", subnet.getId());
        if (request.hostnameLabel() != null) {
            a.put("hostnameLabel", request.hostnameLabel());
        }
        a.put("nsgIds", request.nsgIds() != null ? List.copyOf(request.nsgIds()) : List.of());
        a.put("skipSourceDestCheck", Boolean.TRUE.equals(request.skipSourceDestCheck()));
        a.put("isPrimary", true);
        a.put("macAddress", syntheticMac());
        a.put("ipv6Addresses", List.of());
        String privateIp = privateIp(subnet, request.privateIp(), request.allocatePrivateIp());
        if (privateIp != null) {
            a.put("privateIp", privateIp);
        }
        vnic.setAddressFromWorkload(request.privateIp() == null && !request.allocatePrivateIp());
        if (request.publicIpAssigned()) {
            a.put("publicIp", "203.0.113." + ThreadLocalRandom.current().nextInt(1, 255));
        }
        resources.put(vnic.getId(), vnic);
        LOG.infov("attach vnic {0} on subnet {1}", vnic.getId(), subnet.getId());
        return vnic.getId();
    }

    private String privateIp(StoredNetworkResource subnet, String requested, boolean allocate) {
        Cidr4 cidr = parseCidr((String) subnet.getAttributes().get("cidrBlock"));
        Set<String> taken = new HashSet<>();
        for (StoredNetworkResource vnic : vnicsOn(subnet.getId())) {
            if (vnic.getAttributes().get("privateIp") instanceof String ip) {
                taken.add(ip);
            }
        }
        if (requested != null) {
            Optional<Cidr4> address = Cidr4.parse(requested + "/32");
            if (address.isEmpty() || !cidr.contains(address.get())) {
                throw OciException.invalidParameter("privateIp " + requested + " is not in subnet " + subnet.getId()
                        + " (" + cidr + ")");
            }
            Set<String> reserved = new HashSet<>();
            for (long offset : new long[]{0, 1, cidr.size() - 1}) {
                cidr.addressAt(offset).ifPresent(reserved::add);
            }
            if (reserved.contains(address.get().addressAt(0).orElseThrow())) {
                throw OciException.invalidParameter("privateIp " + requested + " is reserved in subnet "
                        + subnet.getId() + ": the first two and the last address of " + cidr
                        + " belong to the network, the virtual router and the broadcast");
            }
            if (taken.contains(requested)) {
                throw OciException.conflict("privateIp " + requested + " is already in use in subnet " + subnet.getId());
            }
            return requested;
        }
        if (!allocate) {
            return null;
        }
        for (long offset = 2; offset < cidr.size() - 1; offset++) {
            String candidate = cidr.addressAt(offset).orElseThrow();
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        throw OciException.invalidParameter("Subnet " + subnet.getId() + " has no free private IP address");
    }

    @Override
    public synchronized void setVnicPrivateIp(String vnicId, String privateIp) {
        resources.get(vnicId).filter(r -> r.getKind() == NetworkKind.VNIC).ifPresent(found -> {
            if (!found.isAddressFromWorkload() || privateIp == null
                    || privateIp.equals(found.getAttributes().get("privateIp"))) {
                return;
            }
            StoredNetworkResource vnic = found.copy();
            vnic.getAttributes().put("privateIp", privateIp);
            vnic.setEtag(Etags.newEtag());
            resources.put(vnicId, vnic);
        });
    }

    @Override
    public synchronized void detachVnic(String vnicId) {
        if (vnicId != null && resources.get(vnicId).filter(r -> r.getKind() == NetworkKind.VNIC).isPresent()) {
            resources.delete(vnicId);
            LOG.infov("detach vnic {0}", vnicId);
        }
    }

    /** UpdateVnic: names, tags, hostname label, NSGs and the source/destination check (UpdateVnicDetails). */
    public synchronized StoredNetworkResource updateVnic(String vnicId, Map<String, Object> body, String ifMatch) {
        StoredNetworkResource vnic = editable(NetworkKind.VNIC, vnicId);
        Etags.checkIfMatch(ifMatch, vnic.getEtag());
        Map<String, Object> b = body == null ? Map.of() : body;
        if (b.get("nsgIds") instanceof List<?>) {
            requireNsgs(stringList(b.get("nsgIds")));
            requireNsgsInVcn(stringList(b.get("nsgIds")),
                    resources.get(String.valueOf(vnic.getAttributes().get("subnetId")))
                            .map(StoredNetworkResource::getVcnId).orElse(null));
            vnic.getAttributes().put("nsgIds", stringList(b.get("nsgIds")));
        }
        if (str(b, "displayName") != null) {
            vnic.setDisplayName(str(b, "displayName"));
        }
        if (str(b, "hostnameLabel") != null) {
            vnic.getAttributes().put("hostnameLabel", str(b, "hostnameLabel"));
        }
        if (b.get("skipSourceDestCheck") instanceof Boolean skip) {
            vnic.getAttributes().put("skipSourceDestCheck", skip);
        }
        if (b.get("freeformTags") instanceof Map<?, ?>) {
            vnic.setFreeformTags(stringMap(b, "freeformTags"));
        }
        if (b.get("definedTags") instanceof Map<?, ?>) {
            vnic.setDefinedTags(definedTags(b));
        }
        vnic.setEtag(Etags.newEtag());
        resources.put(vnicId, vnic);
        return vnic;
    }

    /** The VNICs whose NSGs include {@code nsgId}, for ListNetworkSecurityGroupVnics. */
    public List<StoredNetworkResource> vnicsInNsg(String nsgId) {
        get(NetworkKind.NETWORK_SECURITY_GROUP, nsgId);
        return resources.scan(k -> true).stream()
                .filter(r -> r.getKind() == NetworkKind.VNIC)
                .filter(r -> stringList(r.getAttributes().get("nsgIds")).contains(nsgId))
                .sorted(Comparator.comparing(StoredNetworkResource::getTimeCreated))
                .toList();
    }

    private List<StoredNetworkResource> vnicsOn(String subnetId) {
        return resources.scan(k -> true).stream()
                .filter(r -> r.getKind() == NetworkKind.VNIC)
                .filter(r -> subnetId.equals(r.getAttributes().get("subnetId")))
                .toList();
    }

    private boolean checksReferences() {
        return config.services().networking().enabled();
    }

    private void requireAll(Collection<String> ids, NetworkKind kind) {
        if (ids == null || !checksReferences()) {
            return;
        }
        for (String id : ids) {
            if (resources.get(id).filter(r -> r.getKind() == kind).isEmpty()) {
                throw OciException.relatedResourceNotAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: " + kind.ocidType() + " " + id);
            }
        }
    }

    private StoredNetworkResource requireVcnInBody(String vcnId) {
        return resources.get(vcnId)
                .filter(r -> r.getKind() == NetworkKind.VCN)
                .orElseThrow(() -> OciException.relatedResourceNotAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: vcn " + vcnId));
    }

    private String ownedRef(Map<String, Object> b, String field, NetworkKind kind, StoredNetworkResource vcn,
                            String fallback) {
        String id = str(b, field);
        if (id == null) {
            return fallback;
        }
        requireOwned(id, kind, vcn, field);
        return id;
    }

    private void requireOwned(String id, NetworkKind kind, StoredNetworkResource vcn, String field) {
        requireOwned(id, kind, vcn.getId(), field);
    }

    private void requireOwned(String id, NetworkKind kind, String vcnId, String field) {
        StoredNetworkResource ref = resources.get(id)
                .filter(r -> r.getKind() == kind)
                .orElseThrow(() -> OciException.relatedResourceNotAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: " + field + " " + id));
        if (!vcnId.equals(ref.getVcnId())) {
            throw OciException.invalidParameter(field + " " + id + " belongs to another VCN");
        }
    }

    /** A VNIC's NSGs must be in its subnet's VCN, as subnets, route tables and security lists are. */
    private void requireNsgsInVcn(Collection<String> nsgIds, String vcnId) {
        if (nsgIds == null || vcnId == null) {
            return;
        }
        for (String nsgId : nsgIds) {
            requireOwned(nsgId, NetworkKind.NETWORK_SECURITY_GROUP, vcnId, "nsgIds");
        }
    }

    private void requireUnreferenced(StoredNetworkResource r, String field) {
        boolean used = resources.scan(k -> true).stream()
                .filter(other -> r.getVcnId().equals(other.getVcnId()))
                .anyMatch(other -> r.getId().equals(other.getAttributes().get(field)));
        if (used) {
            throw OciException.conflict(r.getKind().ocidType() + " " + r.getId() + " is still in use.");
        }
    }

    private List<StoredNetworkResource> children(String vcnId, NetworkKind kind) {
        return resources.scan(k -> true).stream()
                .filter(r -> r.getKind() == kind && vcnId.equals(r.getVcnId()))
                .toList();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private StoredNetworkResource newResource(NetworkKind kind, String compartmentId, String displayName) {
        StoredNetworkResource r = new StoredNetworkResource();
        Instant now = Instant.now();
        r.setId(Ocids.generate(kind.ocidType(), ociContext.realm(), ociContext.regionCode()));
        r.setKind(kind);
        r.setCompartmentId(compartmentId);
        r.setDisplayName(displayName != null ? displayName : kind.ocidType() + NAME_STAMP.format(now));
        r.setLifecycleState("AVAILABLE");
        r.setTimeCreated(now.toString());
        r.setEtag(Etags.newEtag());
        return r;
    }

    private Map<String, Object> service(String cidrLabel, String name, String description) {
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("cidrBlock", cidrLabel);
        service.put("description", description);
        service.put("id", "ocid1.service." + ociContext.realm() + "." + ociContext.regionCode().toLowerCase(Locale.ROOT)
                + "." + stableSuffix(cidrLabel));
        service.put("name", name);
        return service;
    }

    private static String stableSuffix(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            return "aaaaaaaa" + HexFormat.of().formatHex(digest, 0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** OCI virtual router MACs look like {@code 00:00:17:xx:xx:xx}. */
    private static String syntheticMac() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return String.format("00:00:17:%02X:%02X:%02X", random.nextInt(256), random.nextInt(256), random.nextInt(256));
    }

    /** A documentation-range (RFC 5737) address, stable per gateway. */
    private static String syntheticPublicIp(String gatewayId) {
        int hash = gatewayId.hashCode();
        return "203.0.113." + (Math.floorMod(hash, 254) + 1);
    }

    private Cidr4 parseCidr(String text) {
        return Cidr4.parse(text).orElseThrow(() -> OciException.invalidParameter("Invalid CIDR block: " + text));
    }

    private static String dnsLabel(Map<String, Object> b) {
        String label = str(b, "dnsLabel");
        if (label != null && !DNS_LABEL.matcher(label).matches()) {
            throw OciException.invalidParameter("dnsLabel " + label
                    + " must start with a letter, contain only letters and digits, and be at most 15 characters");
        }
        return label;
    }

    private static void requireOneOf(String value, Set<String> allowed, String field) {
        if (!allowed.contains(value)) {
            throw OciException.invalidParameter(field + " must be one of " + allowed + ", got " + value);
        }
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.get(key) != null) {
            to.put(key, from.get(key));
        }
    }

    private static String requireString(Map<String, Object> b, String key) {
        String value = str(b, key);
        if (value == null) {
            throw OciException.missingParameter(key + " is required");
        }
        return value;
    }

    private static List<?> requireList(Map<String, Object> b, String key) {
        if (!(b.get(key) instanceof List<?> list)) {
            throw OciException.missingParameter(key + " is required");
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw OciException.invalidParameter(field + " entries must be objects");
        }
        return (Map<String, Object>) map;
    }

    static String str(Map<String, Object> b, String key) {
        return b != null && b.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance).map(m -> (Map<String, Object>) m).toList();
    }

    private static Map<String, String> stringMap(Map<String, Object> b, String key) {
        Map<String, String> result = new LinkedHashMap<>();
        if (b.get(key) instanceof Map<?, ?> map) {
            map.forEach((k, v) -> result.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> definedTags(Map<String, Object> b) {
        return b.get("definedTags") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Map<String, Object>>) map) : new LinkedHashMap<>();
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return CompartmentUsage.live(resources, tenancyId, compartmentId,
                StoredNetworkResource::getCompartmentId, StoredNetworkResource::getLifecycleState,
                r -> r.getKind().ocidType() + " " + r.getId()).toList();
    }
}
