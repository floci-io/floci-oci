package io.floci.oci.services.governancerules;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.governancerules.model.StoredGovernanceRule;
import io.floci.oci.services.governancerules.model.StoredTenancyAttachment;
import io.floci.oci.services.organizations.OrganizationsService;
import io.floci.oci.services.organizations.model.StoredOrganizationTenancy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class GovernanceRulesServiceTest {

    private static final String PARENT = "ocid1.tenancy.oc1..govparent";

    private final AtomicReference<String> caller = new AtomicReference<>(PARENT);
    private final List<StoredOrganizationTenancy> children = new ArrayList<>();
    private final Map<String, String> parents = new HashMap<>();
    private boolean enabled = true;
    private WorkRequestService workRequests;
    private GovernanceRulesService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        OciContext context = mock(OciContext.class);
        lenient().when(context.tenancyId()).thenAnswer(invocation -> caller.get());
        lenient().when(context.realm()).thenReturn("oc1");
        OrganizationsService organizations = mock(OrganizationsService.class);
        lenient().when(organizations.activeChildren(PARENT)).thenReturn(children);
        lenient().when(organizations.parentOf(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(parents.get(invocation.<String>getArgument(0))));

        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        service = new GovernanceRulesService(partitioned(new InMemoryStorage<>()),
                partitioned(new InMemoryStorage<>()), partitioned(new InMemoryStorage<>()),
                organizations, workRequests, context, () -> enabled);
    }

    private <V> TenancyAwareStorageBackend<V> partitioned(StorageBackend<String, V> raw) {
        return new TenancyAwareStorageBackend<>(raw, caller::get);
    }

    private String createChild(String name) {
        String child = "ocid1.tenancy.oc1.." + name;
        StoredOrganizationTenancy membership = new StoredOrganizationTenancy();
        membership.setTenancyId(child);
        membership.setRole("CHILD");
        membership.setLifecycleState("ACTIVE");
        membership.setGovernanceStatus("OPTED_IN");
        children.add(membership);
        parents.put(child, PARENT);
        return child;
    }

    private StoredGovernanceRule allowedRegions(List<String> regions) {
        return service.createGovernanceRule(PARENT, "regions", null, "ALLOWED_REGIONS", "TEMPLATE",
                Map.of("type", "ALLOWED_REGIONS", "displayName", "regions", "regions", regions),
                null, null, null).resource();
    }

    @Test
    void createGovernanceRuleRecordsAWorkRequestAndStoresTheTemplate() {
        GovernanceRulesService.Created<StoredGovernanceRule> created = service.createGovernanceRule(PARENT,
                "quota", "desc", "QUOTA", "TEMPLATE",
                Map.of("displayName", "q", "statements", List.of("zero compute quotas in tenancy")),
                null, null, null);

        StoredGovernanceRule rule = created.resource();
        assertEquals("ACTIVE", rule.getLifecycleState());
        assertEquals("QUOTA", rule.getTemplate().get("type"));
        assertEquals("CREATE_GOVERNANCE_RULE", workRequests.get(created.workRequestId()).getOperationType());
        assertEquals(1, service.listGovernanceRules(PARENT, null, null, null, "QUOTA", null, null).size());
    }

    @Test
    void templatesAreCheckedAgainstTheRuleType() {
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> service.createGovernanceRule(
                PARENT, "x", null, "TAG", "TEMPLATE", Map.of("type", "QUOTA", "name", "n"), null, null, null))
                .getCode());
        assertEquals("MissingParameter", assertThrows(OciException.class, () -> service.createGovernanceRule(
                PARENT, "x", null, "ALLOWED_REGIONS", "TEMPLATE", Map.of("displayName", "d"), null, null, null))
                .getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> allowedRegions(List.of("mars-north-1"))).getCode());
        assertEquals("MissingParameter", assertThrows(OciException.class, () -> service.createGovernanceRule(
                PARENT, "x", null, "TAG", "CLONE", Map.of("name", "n"), null, null, null)).getCode());
    }

    @Test
    void rulesLiveInTheRootCompartmentOfAParent() {
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> service.createGovernanceRule(
                "ocid1.compartment.oc1..sub", "x", null, "TAG", "TEMPLATE", Map.of("name", "n"), null, null, null))
                .getCode());
        String child = createChild("child");
        caller.set(child);
        assertEquals(404, assertThrows(OciException.class, () -> service.createGovernanceRule(
                child, "x", null, "TAG", "TEMPLATE", Map.of("name", "n"), null, null, null)).getHttpStatus());
    }

    @Test
    void anAllCriterionAttachesTheRuleToEveryOptedInChild() {
        String first = createChild("first");
        String second = createChild("second");
        StoredGovernanceRule rule = allowedRegions(List.of("us-ashburn-1"));

        service.createInclusionCriterion(rule.getId(), "ALL", null, null);

        List<StoredTenancyAttachment> attachments = service.listTenancyAttachments(PARENT, null, rule.getId(),
                "ACTIVE", null, null, null);
        assertEquals(2, attachments.size());
        assertTrue(attachments.stream().allMatch(a -> List.of(first, second).contains(a.getTenancyId())));
    }

    @Test
    void optingOutDetachesTheRule() {
        String child = createChild("child");
        StoredGovernanceRule rule = allowedRegions(List.of("us-ashburn-1"));
        service.createInclusionCriterion(rule.getId(), "TENANCY", "TENANCY", child);

        children.getFirst().setGovernanceStatus("OPTED_OUT");
        service.membershipsChanged(PARENT);

        assertEquals("DELETED", service.listTenancyAttachments(PARENT, null, null, null, child, null, null)
                .getFirst().getLifecycleState());
        assertTrue(service.denial(child, "eu-frankfurt-1").isEmpty());
    }

    @Test
    void allowedRegionsDeniesOtherRegionsToGovernedChildrenOnly() {
        String child = createChild("child");
        StoredGovernanceRule rule = allowedRegions(List.of("us-ashburn-1", "us-phoenix-1"));
        service.createInclusionCriterion(rule.getId(), "TENANCY", null, child);

        Optional<String> denied = service.denial(child, "eu-frankfurt-1");

        assertTrue(denied.isPresent());
        assertTrue(denied.get().contains("eu-frankfurt-1"));
        assertTrue(service.denial(child, "us-phoenix-1").isEmpty());
        assertTrue(service.denial(PARENT, "eu-frankfurt-1").isEmpty(), "the parent is not governed");
    }

    @Test
    void theChildSeesItsEnforcedRules() {
        String child = createChild("child");
        StoredGovernanceRule rule = allowedRegions(List.of("us-ashburn-1"));
        service.createInclusionCriterion(rule.getId(), "ALL", null, null);
        caller.set(child);

        List<Map<String, Object>> enforced = service.listEnforcedGovernanceRules(child, null, null, null, null, null);

        assertEquals(1, enforced.size());
        assertEquals("regions", enforced.getFirst().get("governanceRuleDisplayName"));
        assertEquals(child, enforced.getFirst().get("compartmentId"));
        assertEquals(enforced.getFirst(), service.getEnforcedGovernanceRule((String) enforced.getFirst().get("id")).wire());
        assertEquals(404, assertThrows(OciException.class, () -> service.getGovernanceRule(rule.getId()))
                .getHttpStatus());
    }

    @Test
    void deletingARuleDeletesItsCriteriaAndAttachments() {
        String child = createChild("child");
        StoredGovernanceRule rule = allowedRegions(List.of("us-ashburn-1"));
        service.createInclusionCriterion(rule.getId(), "ALL", null, null);

        String workRequestId = service.deleteGovernanceRule(rule.getId(), null);

        assertEquals("DELETE_GOVERNANCE_RULE", workRequests.get(workRequestId).getOperationType());
        assertEquals("DELETED", service.getGovernanceRule(rule.getId()).getLifecycleState());
        assertTrue(service.listInclusionCriteria(rule.getId(), null, "ACTIVE", null, null).isEmpty());
        assertEquals("DELETED", service.listTenancyAttachments(PARENT, null, null, null, child, null, null)
                .getFirst().getLifecycleState());
        assertEquals("Conflict", assertThrows(OciException.class,
                () -> service.deleteGovernanceRule(rule.getId(), null)).getCode());
    }

    @Test
    void listsRequireACompartmentOrARuleAndValidEnums() {
        assertEquals("MissingParameter", assertThrows(OciException.class,
                () -> service.listGovernanceRules(null, null, null, null, null, null, null)).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.listGovernanceRules(PARENT, null, "CREATING", null, null, null, null)).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.listGovernanceRules(PARENT, null, null, null, null, "id", null)).getCode());
        assertEquals("MissingParameter", assertThrows(OciException.class,
                () -> service.listInclusionCriteria(null, null, null, null, null)).getCode());
    }

    @Test
    void updateReplacesTheTemplateAndChecksTheEtag() {
        StoredGovernanceRule rule = allowedRegions(List.of("us-ashburn-1"));
        String etag = rule.getEtag();

        assertEquals(412, assertThrows(OciException.class, () -> service.updateGovernanceRule(rule.getId(), null,
                null, null, null, null, null, "stale")).getHttpStatus());
        service.updateGovernanceRule(rule.getId(), "renamed", null,
                Map.of("displayName", "regions", "regions", List.of("us-phoenix-1")), null, null, null, etag);

        StoredGovernanceRule updated = service.getGovernanceRule(rule.getId());
        assertEquals("renamed", updated.getDisplayName());
        assertEquals(List.of("us-phoenix-1"), updated.getTemplate().get("regions"));
    }

    @Test
    void nothingIsDeniedWhileGovernanceRulesAreDisabled() {
        String child = createChild("child");
        StoredGovernanceRule rule = allowedRegions(List.of("us-ashburn-1"));
        service.createInclusionCriterion(rule.getId(), "ALL", null, null);

        enabled = false;

        assertTrue(service.denial(child, "eu-frankfurt-1").isEmpty());
    }

    @Test
    void readsDoNotAttachChildrenThatJoinedSinceTheLastChange() {
        StoredGovernanceRule rule = allowedRegions(List.of("us-ashburn-1"));
        service.createInclusionCriterion(rule.getId(), "ALL", null, null);
        String child = createChild("late");

        assertTrue(service.listTenancyAttachments(PARENT, null, null, null, child, null, null).isEmpty());
        service.membershipsChanged(PARENT);
        assertEquals(1, service.listTenancyAttachments(PARENT, null, null, "ACTIVE", child, null, null).size());
    }

    @Test
    void theEnforcedRuleEtagChangesWhenTheRuleChanges() {
        String child = createChild("child");
        StoredGovernanceRule rule = allowedRegions(List.of("us-ashburn-1"));
        service.createInclusionCriterion(rule.getId(), "ALL", null, null);
        caller.set(child);
        String enforcedId = (String) service.listEnforcedGovernanceRules(child, null, null, null, null, null)
                .getFirst().get("id");
        String before = service.getEnforcedGovernanceRule(enforcedId).etag();
        assertEquals(before, service.getEnforcedGovernanceRule(enforcedId).etag(), "stable across reads");

        caller.set(PARENT);
        service.updateGovernanceRule(rule.getId(), null, null,
                Map.of("displayName", "regions", "regions", List.of("us-phoenix-1")), null, null, null, null);
        caller.set(child);

        GovernanceRulesService.Enforced after = service.getEnforcedGovernanceRule(enforcedId);
        assertNotEquals(before, after.etag());
        assertEquals(List.of("us-phoenix-1"), ((Map<?, ?>) after.wire().get("template")).get("regions"));
    }
}
