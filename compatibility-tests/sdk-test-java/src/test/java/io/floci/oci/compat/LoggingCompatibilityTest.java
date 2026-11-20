package io.floci.oci.compat;

import com.oracle.bmc.logging.LoggingManagementClient;
import com.oracle.bmc.logging.model.CreateLogDetails;
import com.oracle.bmc.logging.model.CreateLogGroupDetails;
import com.oracle.bmc.logging.model.Log;
import com.oracle.bmc.logging.model.LogLifecycleState;
import com.oracle.bmc.logging.model.OperationStatus;
import com.oracle.bmc.logging.model.WorkRequest;
import com.oracle.bmc.logging.requests.CreateLogGroupRequest;
import com.oracle.bmc.logging.requests.CreateLogRequest;
import com.oracle.bmc.logging.requests.DeleteLogGroupRequest;
import com.oracle.bmc.logging.requests.DeleteLogRequest;
import com.oracle.bmc.logging.requests.GetLogRequest;
import com.oracle.bmc.logging.requests.GetWorkRequestRequest;
import com.oracle.bmc.loggingingestion.LoggingClient;
import com.oracle.bmc.loggingingestion.model.LogEntry;
import com.oracle.bmc.loggingingestion.model.LogEntryBatch;
import com.oracle.bmc.loggingingestion.model.PutLogsDetails;
import com.oracle.bmc.loggingingestion.requests.PutLogsRequest;
import com.oracle.bmc.loggingsearch.LogSearchClient;
import com.oracle.bmc.loggingsearch.model.SearchLogsDetails;
import com.oracle.bmc.loggingsearch.model.SearchResponse;
import com.oracle.bmc.loggingsearch.requests.SearchLogsRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;

/** Validates Logging management, ingestion and search via oci-java-sdk. */
class LoggingCompatibilityTest {

    private static LoggingManagementClient management;
    private static LoggingClient ingestion;
    private static LogSearchClient search;

    @BeforeAll
    static void setUp() {
        management = EmulatorFixture.loggingManagement();
        ingestion = EmulatorFixture.loggingIngestion();
        search = EmulatorFixture.logSearch();
    }

    @AfterAll
    static void tearDown() {
        management.close();
        ingestion.close();
        search.close();
    }

    /** Mutations answer a work request; the new OCID is its resource's identifier. */
    private static String identifier(String workRequestId) {
        WorkRequest wr = management.getWorkRequest(GetWorkRequestRequest.builder()
                .workRequestId(workRequestId).build()).getWorkRequest();
        assertThat(wr.getStatus()).isEqualTo(OperationStatus.Succeeded);
        return wr.getResources().get(0).getIdentifier();
    }

    @Test
    void createLogPutEntriesAndSearch() {
        String groupName = "java-logs-" + System.nanoTime();
        String groupId = identifier(management.createLogGroup(CreateLogGroupRequest.builder()
                .createLogGroupDetails(CreateLogGroupDetails.builder()
                        .compartmentId(TENANCY).displayName(groupName).build())
                .build()).getOpcWorkRequestId());
        String logId = identifier(management.createLog(CreateLogRequest.builder()
                .logGroupId(groupId)
                .createLogDetails(CreateLogDetails.builder()
                        .displayName("api").logType(CreateLogDetails.LogType.Custom).build())
                .build()).getOpcWorkRequestId());

        Log log = management.getLog(GetLogRequest.builder().logGroupId(groupId).logId(logId).build()).getLog();
        assertThat(log.getLifecycleState()).isEqualTo(LogLifecycleState.Active);
        assertThat(log.getRetentionDuration()).isEqualTo(30);

        // The SDK models take java.util.Date; the conversion stays at this boundary.
        Date now = Date.from(Instant.now().minusSeconds(5));
        ingestion.putLogs(PutLogsRequest.builder()
                .logId(logId)
                .putLogsDetails(PutLogsDetails.builder()
                        .specversion("1.0")
                        .logEntryBatches(List.of(LogEntryBatch.builder()
                                .source("java-compat")
                                .type("com.example.api")
                                .defaultlogentrytime(now)
                                .entries(List.of(
                                        LogEntry.builder().id("1").data("{\"level\":\"ERROR\",\"code\":7}").build(),
                                        LogEntry.builder().id("2").data("{\"level\":\"INFO\",\"code\":0}").build()))
                                .build()))
                        .build())
                .build());

        SearchResponse response = search.searchLogs(SearchLogsRequest.builder()
                .searchLogsDetails(SearchLogsDetails.builder()
                        .timeStart(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)))
                        .timeEnd(Date.from(Instant.now().plus(1, ChronoUnit.MINUTES)))
                        .searchQuery("search \"" + TENANCY + "/" + groupName + "/api\" | where data.level = 'ERROR'")
                        .build())
                .build()).getSearchResponse();
        assertThat(response.getSummary().getResultCount()).isEqualTo(1);
        Map<?, ?> data = (Map<?, ?>) response.getResults().get(0).getData();
        Map<?, ?> content = (Map<?, ?>) data.get("logContent");
        assertThat(((Map<?, ?>) content.get("data")).get("code")).isEqualTo(7);
        assertThat(content.get("source")).isEqualTo("java-compat");

        identifier(management.deleteLog(DeleteLogRequest.builder().logGroupId(groupId).logId(logId).build())
                .getOpcWorkRequestId());
        identifier(management.deleteLogGroup(DeleteLogGroupRequest.builder().logGroupId(groupId).build())
                .getOpcWorkRequestId());
    }
}
