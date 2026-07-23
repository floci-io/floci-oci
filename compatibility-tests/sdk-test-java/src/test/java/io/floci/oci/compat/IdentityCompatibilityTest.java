package io.floci.oci.compat;

import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.AddUserToGroupDetails;
import com.oracle.bmc.identity.model.Compartment;
import com.oracle.bmc.identity.model.CreateCompartmentDetails;
import com.oracle.bmc.identity.model.CreateGroupDetails;
import com.oracle.bmc.identity.model.CreatePolicyDetails;
import com.oracle.bmc.identity.model.CreateUserDetails;
import com.oracle.bmc.identity.model.Group;
import com.oracle.bmc.identity.model.Policy;
import com.oracle.bmc.identity.model.User;
import com.oracle.bmc.identity.model.UserGroupMembership;
import com.oracle.bmc.identity.requests.AddUserToGroupRequest;
import com.oracle.bmc.identity.requests.CreateCompartmentRequest;
import com.oracle.bmc.identity.requests.CreateGroupRequest;
import com.oracle.bmc.identity.requests.CreatePolicyRequest;
import com.oracle.bmc.identity.requests.CreateUserRequest;
import com.oracle.bmc.identity.requests.DeleteCompartmentRequest;
import com.oracle.bmc.identity.requests.DeleteGroupRequest;
import com.oracle.bmc.identity.requests.DeletePolicyRequest;
import com.oracle.bmc.identity.requests.DeleteUserRequest;
import com.oracle.bmc.identity.requests.GetCompartmentRequest;
import com.oracle.bmc.identity.requests.GetUserRequest;
import com.oracle.bmc.identity.requests.GetWorkRequestRequest;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;
import com.oracle.bmc.identity.requests.ListCompartmentsRequest;
import com.oracle.bmc.identity.requests.ListRegionsRequest;
import com.oracle.bmc.identity.requests.ListUserGroupMembershipsRequest;
import com.oracle.bmc.identity.requests.ListUsersRequest;
import com.oracle.bmc.identity.requests.RemoveUserFromGroupRequest;
import com.oracle.bmc.identity.responses.CreateCompartmentResponse;
import com.oracle.bmc.identity.responses.DeleteCompartmentResponse;
import com.oracle.bmc.model.BmcException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validates the Identity API against the real oci-java-sdk. */
class IdentityCompatibilityTest {

    private static IdentityClient identity;

    @BeforeAll
    static void setUp() {
        identity = EmulatorFixture.identity();
    }

    @AfterAll
    static void tearDown() {
        identity.close();
    }

    @Test
    void compartmentCrudAndAsyncDelete() {
        String name = "sdk-compartment-" + System.nanoTime();
        CreateCompartmentResponse created = identity.createCompartment(
                CreateCompartmentRequest.builder()
                        .createCompartmentDetails(CreateCompartmentDetails.builder()
                                .compartmentId(TENANCY)
                                .name(name)
                                .description("sdk compat test")
                                .build())
                        .build());
        Compartment compartment = created.getCompartment();
        assertThat(compartment.getId()).startsWith("ocid1.compartment.");
        assertThat(compartment.getLifecycleState()).isEqualTo(Compartment.LifecycleState.Active);
        assertThat(created.getEtag()).isNotBlank();
        assertThat(created.getOpcRequestId()).isNotBlank();

        Compartment fetched = identity.getCompartment(GetCompartmentRequest.builder()
                .compartmentId(compartment.getId()).build()).getCompartment();
        assertThat(fetched.getName()).isEqualTo(name);

        List<Compartment> listed = identity.listCompartments(ListCompartmentsRequest.builder()
                .compartmentId(TENANCY).build()).getItems();
        assertThat(listed).extracting(Compartment::getId).contains(compartment.getId());

        DeleteCompartmentResponse deleted = identity.deleteCompartment(
                DeleteCompartmentRequest.builder().compartmentId(compartment.getId()).build());
        assertThat(deleted.getOpcWorkRequestId()).startsWith("ocid1.");

        var workRequest = identity.getWorkRequest(GetWorkRequestRequest.builder()
                .workRequestId(deleted.getOpcWorkRequestId()).build()).getWorkRequest();
        assertThat(workRequest.getStatus().getValue()).isEqualTo("SUCCEEDED");
    }

    @Test
    void userGroupMembershipRoundtrip() {
        String suffix = String.valueOf(System.nanoTime());
        User user = identity.createUser(CreateUserRequest.builder()
                .createUserDetails(CreateUserDetails.builder()
                        .compartmentId(TENANCY)
                        .name("sdk-user-" + suffix)
                        .description("sdk user")
                        .email("sdk@example.com")
                        .build())
                .build()).getUser();
        assertThat(user.getId()).startsWith("ocid1.user.");
        assertThat(user.getIsMfaActivated()).isFalse();

        Group group = identity.createGroup(CreateGroupRequest.builder()
                .createGroupDetails(CreateGroupDetails.builder()
                        .compartmentId(TENANCY)
                        .name("sdk-group-" + suffix)
                        .description("sdk group")
                        .build())
                .build()).getGroup();

        UserGroupMembership membership = identity.addUserToGroup(AddUserToGroupRequest.builder()
                .addUserToGroupDetails(AddUserToGroupDetails.builder()
                        .userId(user.getId())
                        .groupId(group.getId())
                        .build())
                .build()).getUserGroupMembership();
        assertThat(membership.getUserId()).isEqualTo(user.getId());

        List<UserGroupMembership> memberships = identity.listUserGroupMemberships(
                ListUserGroupMembershipsRequest.builder()
                        .compartmentId(TENANCY)
                        .userId(user.getId())
                        .build()).getItems();
        assertThat(memberships).hasSize(1);

        identity.removeUserFromGroup(RemoveUserFromGroupRequest.builder()
                .userGroupMembershipId(membership.getId()).build());
        identity.deleteUser(DeleteUserRequest.builder().userId(user.getId()).build());
        identity.deleteGroup(DeleteGroupRequest.builder().groupId(group.getId()).build());

        assertThatThrownBy(() -> identity.getUser(
                GetUserRequest.builder().userId(user.getId()).build()))
                .isInstanceOfSatisfying(BmcException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getServiceCode()).isEqualTo("NotAuthorizedOrNotFound");
                });
    }

    @Test
    void policyLifecycle() {
        Policy policy = identity.createPolicy(CreatePolicyRequest.builder()
                .createPolicyDetails(CreatePolicyDetails.builder()
                        .compartmentId(TENANCY)
                        .name("sdk-policy-" + System.nanoTime())
                        .description("sdk policy")
                        .statements(List.of(
                                "Allow group Administrators to manage all-resources in tenancy"))
                        .build())
                .build()).getPolicy();
        assertThat(policy.getStatements()).hasSize(1);
        identity.deletePolicy(DeletePolicyRequest.builder().policyId(policy.getId()).build());
    }

    @Test
    void listUsersPaginates() {
        var response = identity.listUsers(ListUsersRequest.builder()
                .compartmentId(TENANCY).limit(1).build());
        assertThat(response.getOpcRequestId()).isNotBlank();
    }

    @Test
    void referenceData() {
        var regions = identity.listRegions(ListRegionsRequest.builder().build()).getItems();
        assertThat(regions).anySatisfy(r -> assertThat(r.getName()).isEqualTo("us-ashburn-1"));

        var ads = identity.listAvailabilityDomains(ListAvailabilityDomainsRequest.builder()
                .compartmentId(TENANCY).build()).getItems();
        assertThat(ads).hasSize(3);
    }
}
