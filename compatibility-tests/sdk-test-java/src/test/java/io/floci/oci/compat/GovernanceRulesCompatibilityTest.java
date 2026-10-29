package io.floci.oci.compat;

import com.oracle.bmc.governancerulescontrolplane.model.AllowedRegionsTemplate;
import com.oracle.bmc.governancerulescontrolplane.model.CreateGovernanceRuleDetails;
import com.oracle.bmc.governancerulescontrolplane.model.CreateInclusionCriterionDetails;
import com.oracle.bmc.governancerulescontrolplane.model.CreationOption;
import com.oracle.bmc.governancerulescontrolplane.model.GovernanceRuleSummary;
import com.oracle.bmc.governancerulescontrolplane.model.GovernanceRuleType;
import com.oracle.bmc.governancerulescontrolplane.model.InclusionCriterion;
import com.oracle.bmc.governancerulescontrolplane.model.TenancyAssociation;
import com.oracle.bmc.governancerulescontrolplane.requests.CreateGovernanceRuleRequest;
import com.oracle.bmc.governancerulescontrolplane.requests.CreateInclusionCriterionRequest;
import com.oracle.bmc.governancerulescontrolplane.requests.DeleteGovernanceRuleRequest;
import com.oracle.bmc.governancerulescontrolplane.requests.GetGovernanceRuleRequest;
import com.oracle.bmc.governancerulescontrolplane.requests.GetWorkRequestRequest;
import com.oracle.bmc.governancerulescontrolplane.requests.ListGovernanceRulesRequest;
import com.oracle.bmc.governancerulescontrolplane.requests.ListInclusionCriteriaRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Validates the Governance Rules control plane against the real oci-java-sdk. */
class GovernanceRulesCompatibilityTest {

    @Test
    void allowedRegionsRuleLifecycle() {
        String parent = "ocid1.tenancy.oc1..compatgov" + System.nanoTime();
        try (var rules = EmulatorFixture.governanceRulesAs(parent);
             var workRequests = EmulatorFixture.governanceWorkRequestsAs(parent)) {
            var created = rules.createGovernanceRule(CreateGovernanceRuleRequest.builder()
                    .createGovernanceRuleDetails(CreateGovernanceRuleDetails.builder()
                            .compartmentId(parent)
                            .displayName("only-ashburn")
                            .type(GovernanceRuleType.AllowedRegions)
                            .creationOption(CreationOption.Template)
                            .template(AllowedRegionsTemplate.builder()
                                    .displayName("only-ashburn")
                                    .regions(List.of("us-ashburn-1"))
                                    .build())
                            .build())
                    .build());
            assertThat(created.getEtag()).isNotBlank();
            assertThat(created.getOpcWorkRequestId()).isNotBlank();
            String ruleId = created.getGovernanceRule().getId();
            assertThat(created.getGovernanceRule().getTemplate()).isInstanceOf(AllowedRegionsTemplate.class);

            var workRequest = workRequests.getWorkRequest(GetWorkRequestRequest.builder()
                    .workRequestId(created.getOpcWorkRequestId()).build()).getWorkRequest();
            assertThat(workRequest.getStatus().getValue()).isEqualTo("SUCCEEDED");
            assertThat(workRequest.getOperationType().getValue()).isEqualTo("CREATE_GOVERNANCE_RULE");

            var criterion = rules.createInclusionCriterion(CreateInclusionCriterionRequest.builder()
                    .createInclusionCriterionDetails(CreateInclusionCriterionDetails.builder()
                            .governanceRuleId(ruleId)
                            .type(InclusionCriterion.Type.Tenancy)
                            .association(TenancyAssociation.builder()
                                    .tenancyId("ocid1.tenancy.oc1..compatgovchild").build())
                            .build())
                    .build()).getInclusionCriterion();
            assertThat(criterion.getType()).isEqualTo(InclusionCriterion.Type.Tenancy);
            assertThat(criterion.getAssociation()).isInstanceOf(TenancyAssociation.class);

            assertThat(rules.listInclusionCriteria(ListInclusionCriteriaRequest.builder()
                    .governanceRuleId(ruleId).build()).getInclusionCriterionCollection().getItems()).hasSize(1);
            assertThat(rules.listGovernanceRules(ListGovernanceRulesRequest.builder().compartmentId(parent).build())
                    .getGovernanceRuleCollection().getItems())
                    .extracting(GovernanceRuleSummary::getId).containsExactly(ruleId);

            var deleted = rules.deleteGovernanceRule(DeleteGovernanceRuleRequest.builder()
                    .governanceRuleId(ruleId).build());
            assertThat(deleted.getOpcWorkRequestId()).isNotBlank();
            assertThat(rules.getGovernanceRule(GetGovernanceRuleRequest.builder().governanceRuleId(ruleId).build())
                    .getGovernanceRule().getLifecycleState().getValue()).isEqualTo("DELETED");
        }
    }
}
