package io.floci.oci.compat;

import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.requests.GetTenancyRequest;
import com.oracle.bmc.identity.requests.ListCompartmentsRequest;
import com.oracle.bmc.tenantmanagercontrolplane.LinkClient;
import com.oracle.bmc.tenantmanagercontrolplane.OrganizationClient;
import com.oracle.bmc.tenantmanagercontrolplane.WorkRequestClient;
import com.oracle.bmc.tenantmanagercontrolplane.model.CreateChildTenancyDetails;
import com.oracle.bmc.tenantmanagercontrolplane.model.LinkSummary;
import com.oracle.bmc.tenantmanagercontrolplane.model.OrganizationTenancySummary;
import com.oracle.bmc.tenantmanagercontrolplane.requests.CreateChildTenancyRequest;
import com.oracle.bmc.tenantmanagercontrolplane.requests.GetOrganizationRequest;
import com.oracle.bmc.tenantmanagercontrolplane.requests.GetWorkRequestRequest;
import com.oracle.bmc.tenantmanagercontrolplane.requests.ListLinksRequest;
import com.oracle.bmc.tenantmanagercontrolplane.requests.ListOrganizationTenanciesRequest;
import com.oracle.bmc.tenantmanagercontrolplane.requests.ListOrganizationsRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;

/** Validates Organizations (Tenant Manager control plane) and child tenancy isolation against the real oci-java-sdk. */
class OrganizationsCompatibilityTest {

    private static OrganizationClient organizations;
    private static LinkClient links;
    private static WorkRequestClient workRequests;

    @BeforeAll
    static void setUp() {
        organizations = EmulatorFixture.organization();
        links = EmulatorFixture.link();
        workRequests = EmulatorFixture.organizationsWorkRequests();
    }

    @AfterAll
    static void tearDown() {
        organizations.close();
        links.close();
        workRequests.close();
    }

    private static String createChild(String name) {
        var created = organizations.createChildTenancy(CreateChildTenancyRequest.builder()
                .createChildTenancyDetails(CreateChildTenancyDetails.builder()
                        .compartmentId(TENANCY)
                        .tenancyName(name)
                        .homeRegion("us-ashburn-1")
                        .adminEmail("admin@example.com")
                        .features(List.of("SUBSCRIPTION_SHARING"))
                        .build())
                .build());
        assertThat(created.getOpcWorkRequestId()).isNotBlank();

        var workRequest = workRequests.getWorkRequest(GetWorkRequestRequest.builder()
                .workRequestId(created.getOpcWorkRequestId()).build()).getWorkRequest();
        assertThat(workRequest.getStatus().getValue()).isEqualTo("SUCCEEDED");
        assertThat(workRequest.getOperationType().getValue()).isEqualTo("CREATE_CHILD_TENANCY");
        assertThat(workRequest.getResources()).isNotEmpty();
        return workRequest.getResources().get(0).getIdentifier();
    }

    @Test
    void createChildTenancyFormsAnOrganizationWithLinks() {
        String child = createChild("compat-child-" + System.nanoTime());

        var organizationList = organizations.listOrganizations(ListOrganizationsRequest.builder()
                .compartmentId(TENANCY).build()).getOrganizationCollection().getItems();
        assertThat(organizationList).hasSize(1);
        String organizationId = organizationList.get(0).getId();

        var organization = organizations.getOrganization(GetOrganizationRequest.builder()
                .organizationId(organizationId).build());
        assertThat(organization.getEtag()).isNotBlank();
        assertThat(organization.getOrganization().getCompartmentId()).isEqualTo(TENANCY);
        assertThat(organization.getOrganization().getLifecycleState().getValue()).isEqualTo("ACTIVE");

        List<OrganizationTenancySummary> members = organizations.listOrganizationTenancies(
                ListOrganizationTenanciesRequest.builder().organizationId(organizationId).build())
                .getOrganizationTenancyCollection().getItems();
        assertThat(members).extracting(OrganizationTenancySummary::getTenancyId).contains(TENANCY, child);

        List<LinkSummary> childLinks = links.listLinks(ListLinksRequest.builder()
                .childTenancyId(child).build()).getLinkCollection().getItems();
        assertThat(childLinks).extracting(LinkSummary::getFeature).containsExactlyInAnyOrder("CORE", "SUBSCRIPTION_SHARING");
    }

    @Test
    void aChildTenancyIsIsolatedWithItsOwnHomeRegion() {
        String child = createChild("compat-isolated-" + System.nanoTime());

        try (IdentityClient asChild = EmulatorFixture.identityAs(child)) {
            var tenancy = asChild.getTenancy(GetTenancyRequest.builder().tenancyId(child).build()).getTenancy();
            assertThat(tenancy.getId()).isEqualTo(child);
            assertThat(tenancy.getHomeRegionKey()).isEqualTo("IAD");

            var compartments = asChild.listCompartments(ListCompartmentsRequest.builder()
                    .compartmentId(child).compartmentIdInSubtree(true).build()).getItems();
            assertThat(compartments).allSatisfy(c -> assertThat(c.getCompartmentId()).isEqualTo(child));
        }
    }
}
