# Networking (VCN)

OCI Core Virtual Network, API version `20160918`. Real OCI serves it on the
`iaas.<region>` host under the same version prefix as Identity; floci-oci serves both on
port 4599. Resources are kept as state only: no Docker network is created for a VCN.

## Supported operations

| Area | Operations |
|---|---|
| VCNs | CreateVcn, GetVcn, ListVcns, UpdateVcn, DeleteVcn, ChangeVcnCompartment, GetVcnDnsResolverAssociation |
| Subnets | CreateSubnet, GetSubnet, ListSubnets, UpdateSubnet, DeleteSubnet, ChangeSubnetCompartment |
| Gateways | Internet, NAT and service gateways: Create, Get, List, Update, Delete, ChangeCompartment; AttachServiceId, DetachServiceId |
| Services | ListServices, GetService |
| Route tables | Create, Get, List, Update, Delete, ChangeCompartment |
| Security lists | Create, Get, List, Update, Delete, ChangeCompartment |
| Network security groups | Create, Get, List, Update, Delete, ChangeCompartment; AddNetworkSecurityGroupSecurityRules, UpdateNetworkSecurityGroupSecurityRules, RemoveNetworkSecurityGroupSecurityRules, ListNetworkSecurityGroupSecurityRules, ListNetworkSecurityGroupVnics |
| VNICs | GetVnic, UpdateVnic (VNICs are created by the services that attach them) |
| DHCP options | Create, Get, List, Update, Delete, ChangeCompartment |

## Wire notes

- Create, get and update return the resource with an `etag` header (CreateServiceGateway
  returns none); delete returns `204`. Lists are **bare JSON arrays** paginated with
  `opc-next-page`. `sortBy=TIMECREATED` sorts newest first, `DISPLAYNAME` A to Z.
- Every resource is `AVAILABLE` as soon as it is created. A deleted resource reads as
  `404 NotAuthorizedOrNotFound`, which Terraform accepts as gone.
- **CreateVcn makes three defaults** and returns their OCIDs as
  `defaultRouteTableId`, `defaultSecurityListId` and `defaultDhcpOptionsId`:
  `Default Route Table for <name>` (no rules), `Default Security List for <name>`
  (ingress TCP 22 and ICMP 3/4 from anywhere, ICMP 3 from the VCN, all egress) and
  `Default DHCP Options for <name>` (`VcnLocalPlusInternet`, plus a
  `<dnsLabel>.oraclevcn.com` search domain when the VCN has a DNS label). Defaults cannot be
  deleted on their own; they go with the VCN.
- A subnet without `routeTableId`, `securityListIds` or `dhcpOptionsId` uses the VCN's
  defaults. `virtualRouterIp` is the first host address of the subnet.
- Route rules come back with both `cidrBlock` and `destination`, `destinationType`
  (`CIDR_BLOCK` by default) and `routeType: STATIC`, as Terraform expects.
- ListServices returns `All <CODE> Services In Oracle Services Network` and
  `OCI <CODE> Object Storage` for the region; their OCIDs are stable across restarts.
- VCN and subnet compartment moves return `202` with `opc-work-request-id`, polled at
  `/20160918/workRequests/{id}` (the path the Core work-request client uses). The other
  compartment moves return `200`.

## DNS resolver association

Every VCN gets a VCN-dedicated DNS resolver with a protected default view, created by the
[DNS](dns.md) service when the VCN is created and removed when it is deleted.
`GET /20160918/vcns/{vcnId}/dnsResolverAssociation` answers `vcnId`, `dnsResolverId` and
`lifecycleState: AVAILABLE`, with the VCN's `etag`. A VCN created while DNS was disabled gets its
resolver the first time the association is read; with DNS disabled the association has no
`dnsResolverId`.

## Validation

| Request | Result |
|---|---|
| VCN CIDR outside `/16` to `/30`, or overlapping CIDR blocks | `400 InvalidParameter` |
| Subnet CIDR outside the VCN, or overlapping another subnet | `400 InvalidParameter` |
| `dnsLabel` not a letter followed by letters and digits (15 max), or reused in the VCN | `400 InvalidParameter` |
| Route rule with different `cidrBlock` and `destination` | `400 InvalidParameter` |
| Unknown `vcnId`, route table, security list, DHCP options, gateway or service in a body | `400 RelatedResourceNotAuthorizedOrNotFound` |
| Delete a VCN that still has subnets, gateways or non-default resources | `409 Conflict` |
| Delete a route table, security list or DHCP options a subnet uses, or a gateway a route rule targets | `409 Conflict` |
| Delete a VCN, subnet or network security group another service's resource still uses (an OKE cluster, a function application, a PostgreSQL or MySQL DB system, a container instance's VNIC) | `409 Conflict` |

A resource that another service holds a reference to stays until that resource is deleted: the
`409 Conflict` names each one (`application ocid1.fnapp...`). A deleted resource no longer
counts, but one that is still `DELETING` does, as in OCI, where the Terraform provider retries a
subnet delete on `Conflict` while its dependants are torn down. The status and code follow OCI;
the message wording is floci-oci's own (inferred, no source shows OCI's text).

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

VCN=$(oci network vcn create $E --compartment-id "$TENANCY" --cidr-blocks '["10.0.0.0/16"]' \
  --dns-label main --query data.id --raw-output)
oci network subnet create $E --compartment-id "$TENANCY" --vcn-id "$VCN" \
  --cidr-block 10.0.1.0/24 --dns-label app
oci network internet-gateway create $E --compartment-id "$TENANCY" --vcn-id "$VCN" --is-enabled true
```

With Terraform, add `oci_core.VirtualNetworkClient` to `client_host_overrides` in the
provider block.

## VNICs

A VNIC is created by the service that places something on a subnet, today
[Container Instances](container-instances.md), and deleted with it; there is no CreateVnic.
`GetVnic` returns the `Vnic` shape of oci-go-sdk/core/vnic.go: `subnetId`, `availabilityDomain`,
`privateIp`, `hostnameLabel`, `nsgIds`, `skipSourceDestCheck`, `isPrimary: true`, a synthetic
`macAddress`, and `publicIp` only when one was asked for. The private IP is the one requested, the
address the workload really got (a container instance in real mode), or else the next free one in
the subnet, skipping the first two and the last address, which OCI keeps for the network, the
virtual router and the broadcast. A requested address outside the subnet, or one of those three
reserved addresses, is `400 InvalidParameter`, one already in use `409 Conflict`. Oracle's VCN
overview documents the reservation; the error code for requesting a reserved address is inferred.
A workload address changes with the workload (a restarted pod can get another); a requested or
allocated one never does. A public IP, when asked for, comes from
`203.0.113.0/24` (documentation addresses, RFC 5737): nothing floci-oci hands out is publicly
routable. `UpdateVnic` changes the display name, hostname label, NSGs, `skipSourceDestCheck` and
tags, and checks `if-match`. On attach and on update every NSG must be in the subnet's VCN, or the
request is `400 InvalidParameter` (`nsgIds <id> belongs to another VCN`, the rule subnets already
apply to route tables and security lists; inferred, as no source states the error). `ListNetworkSecurityGroupVnics` lists the VNICs in a group. A subnet
with VNICs cannot be deleted (`409 Conflict`).

## Notes and limitations

VCN and subnet CIDR changes (add, modify and remove CIDR, IPv6), VLANs, DRGs, local peering
gateways, and private and public IP objects are not implemented.

OKE, Functions and Database with PostgreSQL check every VCN, subnet and network security group
OCID they receive against this service: one that does not exist in the caller's tenancy and region
is rejected with `400 RelatedResourceNotAuthorizedOrNotFound`. With Networking disabled
(`FLOCI_OCI_SERVICES_NETWORKING_ENABLED=false`) they accept any OCID.
