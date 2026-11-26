package io.floci.oci.services.streaming;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.streaming.model.StoredStream;
import io.floci.oci.services.streaming.model.StoredStream.StoredEntry;
import io.floci.oci.services.streaming.model.StoredStreamPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The real-mode lifecycle, with the broker manager mocked: pools and streams wait for Redpanda. */
class StreamingKafkaServiceTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..kafkatestcompartment";
    private static final String TENANCY = "ocid1.tenancy.oc1..kafkatesttenancy";
    private static final String BOOTSTRAP = "localhost:19092";

    private StreamingService service;
    private WorkRequestService workRequests;
    private StreamPoolKafkaManager kafka;
    private EmulatorConfig config;
    private InMemoryStorage<String, StoredStream> streams;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.effectiveBaseUrl()).thenReturn("http://localhost:4599");
        lenient().when(config.services().streaming().mock()).thenReturn(false);
        lenient().when(config.services().streaming().readyTimeoutSeconds()).thenReturn(60);
        lenient().when(config.storage().mode()).thenReturn("memory");
        kafka = mock(StreamPoolKafkaManager.class);
        doAnswer(invocation -> {
            StoredStreamPool pool = invocation.getArgument(0);
            pool.setKafkaHostPort(19092);
            pool.setProxyEndpoint("localhost:18082");
            pool.setBrokerStartedAt(Instant.now().toString());
            return null;
        }).when(kafka).start(any());
        lenient().when(kafka.isRunning(any())).thenReturn(true);
        lenient().when(kafka.bootstrapServers(any())).thenReturn(BOOTSTRAP);
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        streams = new InMemoryStorage<>();
        service = new StreamingService(streams, new InMemoryStorage<>(), config, workRequests, kafka,
                Clock.systemUTC(), Runnable::run);
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes());
    }

    @Test
    void poolIsCreatingUntilItsBrokerAnswers() {
        StreamingService.PoolChange created = service.createStreamPool("kafka-pool", COMPARTMENT,
                null, null, null, null);
        assertEquals("CREATING", created.pool().getLifecycleState());
        StoredWorkRequest wr = workRequests.get("streaming", created.workRequestId());
        assertEquals("IN_PROGRESS", wr.getStatus());
        assertEquals("stream_pool", wr.getResources().get(0).getEntityType());
        verify(kafka).start(any());

        when(kafka.isReady(any())).thenReturn(false);
        service.tick();
        assertEquals("CREATING", service.getStreamPool(created.pool().getId()).getLifecycleState());

        when(kafka.isReady(any())).thenReturn(true);
        service.tick();
        StoredStreamPool pool = service.getStreamPool(created.pool().getId());
        assertEquals("ACTIVE", pool.getLifecycleState());
        assertEquals(BOOTSTRAP, pool.getBootstrapServers());
        assertEquals("SUCCEEDED", workRequests.get(created.workRequestId()).getStatus());
        assertNotNull(workRequests.get(created.workRequestId()).getTimeFinished());
    }

    @Test
    void streamWaitsForItsPoolAndGetsATopic() {
        StoredStreamPool pool = service.createStreamPool("p", COMPARTMENT, null, null, null, null).pool();
        StreamingService.CreatedStream created = service.createStream("orders", 3, 48, null, pool.getId(),
                null, null);
        assertEquals("CREATING", created.stream().getLifecycleState());
        assertEquals(StoredStream.LOG_REDPANDA, created.stream().getLogBackend());
        assertEquals("IN_PROGRESS", workRequests.get(created.workRequestId()).getStatus());

        when(kafka.isReady(any())).thenReturn(false);
        service.tick();
        verify(kafka, never()).createTopic(any(), any(), anyInt(), anyInt());

        when(kafka.isReady(any())).thenReturn(true);
        service.tick();
        verify(kafka).createTopic(any(), eq("orders"), eq(3), eq(48));
        assertEquals("ACTIVE", service.getStream(created.stream().getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequests.get(created.workRequestId()).getStatus());
    }

    @Test
    void pooledStreamsReadAndWriteThroughTheBroker() {
        StoredStream stream = activeStream("events", 1);
        when(kafka.produce(any(), eq("events"), any())).thenReturn(List.of(
                new KafkaRestClient.RecordOffset(0, 7, null), new KafkaRestClient.RecordOffset(0, 8, null)));

        List<StreamingService.PutEntryResult> put = service.putMessages(stream.getId(), List.of(
                Map.of("value", b64("a")), Map.of("value", "%%not-base64%%"), Map.of("value", b64("b"))));
        assertEquals(7L, put.get(0).offset());
        assertEquals("InvalidParameter", put.get(1).error());
        assertEquals(8L, put.get(2).offset());

        StoredEntry entry = new StoredEntry();
        entry.setOffset(7);
        entry.setValue(b64("a"));
        entry.setTimestamp("2026-11-25T00:00:00Z");
        when(kafka.read(any(), eq("events"), eq(0), eq(7L), anyInt())).thenReturn(List.of(entry));
        when(kafka.endOffset(any(), eq("events"), eq(0))).thenReturn(9L);

        String cursor = service.createCursor(stream.getId(), "0", "AT_OFFSET", 7L, null);
        StreamingService.ConsumeResult read = service.getMessages(stream.getId(), cursor, 10);
        assertEquals(1, read.messages().size());
        assertEquals(7L, read.messages().get(0).get("offset"));
        assertEquals("2026-11-25T00:00:00Z", read.messages().get(0).get("timestamp"));

        String latest = service.createCursor(stream.getId(), "0", "LATEST", null, null);
        when(kafka.read(any(), eq("events"), eq(0), eq(9L), anyInt())).thenReturn(List.of());
        assertTrue(service.getMessages(stream.getId(), latest, 10).messages().isEmpty());
    }

    @Test
    void aStreamIsRefusedUntilItIsActive() {
        StoredStreamPool pool = service.createStreamPool("p", COMPARTMENT, null, null, null, null).pool();
        StoredStream stream = service.createStream("waiting", 1, null, null, pool.getId(), null, null).stream();
        OciException e = assertThrows(OciException.class,
                () -> service.putMessages(stream.getId(), List.of(Map.of("value", b64("x")))));
        assertEquals("IncorrectState", e.getCode());
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void aBrokerThatExitsFailsThePoolAndItsStreams() {
        StreamingService.PoolChange created = service.createStreamPool("p", COMPARTMENT, null, null, null, null);
        StreamingService.CreatedStream stream = service.createStream("doomed", 1, null, null,
                created.pool().getId(), null, null);
        when(kafka.isReady(any())).thenReturn(false);
        when(kafka.isRunning(any())).thenReturn(false);

        service.tick();
        StoredStreamPool pool = service.getStreamPool(created.pool().getId());
        assertEquals("FAILED", pool.getLifecycleState());
        assertNotNull(pool.getLifecycleStateDetails());
        assertEquals("FAILED", workRequests.get(created.workRequestId()).getStatus());

        service.tick();
        assertEquals("FAILED", service.getStream(stream.stream().getId()).getLifecycleState());
        assertEquals("FAILED", workRequests.get(stream.workRequestId()).getStatus());
    }

    @Test
    void aBrokerPastTheReadyTimeoutFailsThePool() {
        Clock late = Clock.fixed(Instant.now().plusSeconds(3600), ZoneOffset.UTC);
        StreamingService slow = new StreamingService(new InMemoryStorage<>(), new InMemoryStorage<>(), config,
                workRequests, kafka, late, Runnable::run);
        StoredStreamPool pool = slow.createStreamPool("slow", COMPARTMENT, null, null, null, null).pool();
        when(kafka.isReady(any())).thenReturn(false);
        slow.tick();
        assertEquals("FAILED", slow.getStreamPool(pool.getId()).getLifecycleState());
    }

    @Test
    void aSecondStreamWithTheSameNameInOnePoolIsAConflict() {
        StoredStreamPool pool = service.createStreamPool("p", COMPARTMENT, null, null, null, null).pool();
        service.createStream("dup", 1, null, null, pool.getId(), null, null);
        OciException e = assertThrows(OciException.class,
                () -> service.createStream("dup", 1, null, null, pool.getId(), null, null));
        assertEquals("Conflict", e.getCode());
    }

    @Test
    void aDefaultPoolStartsABrokerOnFirstUse() {
        StoredStream stream = service.createStream("pool-less", 1, null, COMPARTMENT, null, null, null).stream();
        StoredStreamPool pool = service.getStreamPool(stream.getStreamPoolId());
        assertEquals("DefaultPool", pool.getName());
        assertEquals("CREATING", pool.getLifecycleState());
        verify(kafka).start(any());
    }

    @Test
    void deletingStreamsAndPoolsRemovesTopicsAndBrokers() {
        StoredStream stream = activeStream("gone", 1);
        service.deleteStream(stream.getId(), null);
        verify(kafka).deleteTopic(any(), eq("gone"));

        service.deleteStreamPool(stream.getStreamPoolId(), null);
        verify(kafka).remove(any());
    }

    @Test
    void streamsStoredBeforeTheSwitchKeepTheirStoredLog() {
        StoredStream legacy = new StoredStream();
        legacy.setId("ocid1.stream.oc1.iad.legacy");
        legacy.setName("legacy");
        legacy.setCompartmentId(COMPARTMENT);
        legacy.setStreamPoolId("ocid1.streampool.oc1.iad.legacy");
        legacy.setPartitions(1);
        legacy.setLifecycleState("ACTIVE");
        legacy.setTimeCreated("2026-01-01T00:00:00Z");
        streams.put(legacy.getId(), legacy);

        List<StreamingService.PutEntryResult> put = service.putMessages(legacy.getId(),
                List.of(Map.of("value", b64("kept"))));
        assertEquals(0L, put.get(0).offset());
        verify(kafka, never()).produce(any(), any(), any());
        String cursor = service.createCursor(legacy.getId(), "0", "TRIM_HORIZON", null, null);
        assertEquals(1, service.getMessages(legacy.getId(), cursor, 10).messages().size());
        verify(kafka, never()).read(any(), any(), anyInt(), anyLong(), anyInt());
    }

    @Test
    void resetPurgesEveryBroker() {
        service.createStreamPool("p", COMPARTMENT, null, null, null, null);
        service.clear();
        verify(kafka).purge(any());
        assertTrue(service.listStreamPools(COMPARTMENT, null, null, null, null, null).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aGroupReadBuffersNoMoreRecordsThanTheLimitAcrossPartitions() {
        StoredStream stream = activeStream("wide", 20);
        AtomicInteger loaded = new AtomicInteger();
        when(kafka.read(any(), eq("wide"), anyInt(), anyLong(), anyInt())).thenAnswer(invocation -> {
            long from = invocation.getArgument(3);
            int max = invocation.getArgument(4);
            List<StoredEntry> entries = new ArrayList<>();
            for (long offset = from; offset < from + max; offset++) {
                StoredEntry entry = new StoredEntry();
                entry.setOffset(offset);
                entry.setValue(b64("m" + offset));
                entry.setTimestamp("2026-11-25T00:00:00Z");
                entries.add(entry);
            }
            loaded.addAndGet(entries.size());
            return entries;
        });

        String cursor = service.createGroupCursor(stream.getId(), "g", "TRIM_HORIZON", null, false);
        StreamingService.ConsumeResult read = service.getMessages(stream.getId(), cursor, 100);

        assertEquals(100, read.messages().size());
        assertEquals(100, loaded.get());
        for (int i = 0; i < 20; i++) {
            assertEquals(String.valueOf(i), read.messages().get(i).get("partition"));
            assertEquals(0L, read.messages().get(i).get("offset"));
        }
        assertEquals("1", read.messages().get(21).get("partition"));
        assertEquals(1L, read.messages().get(21).get("offset"));

        service.consumerCommit(stream.getId(), read.nextCursor());
        Map<String, Object> group = service.getGroup(stream.getId(), "g");
        List<Map<String, Object>> reservations = (List<Map<String, Object>>) group.get("reservations");
        assertEquals(20, reservations.size());
        reservations.forEach(reservation -> assertEquals(5L, reservation.get("committedOffset")));
    }

    @Test
    void aGroupReadRefillsTheOnlyPartitionWithRecordsUpToTheLimit() {
        StoredStream stream = activeStream("skewed", 4);
        AtomicInteger loaded = new AtomicInteger();
        when(kafka.read(any(), eq("skewed"), anyInt(), anyLong(), anyInt())).thenAnswer(invocation -> {
            int partition = invocation.getArgument(2);
            long from = invocation.getArgument(3);
            int max = invocation.getArgument(4);
            List<StoredEntry> entries = new ArrayList<>();
            for (long offset = from; partition == 0 && offset < Math.min(from + max, 50); offset++) {
                StoredEntry entry = new StoredEntry();
                entry.setOffset(offset);
                entry.setValue(b64("m" + offset));
                entry.setTimestamp("2026-11-25T00:00:00Z");
                entries.add(entry);
            }
            loaded.addAndGet(entries.size());
            return entries;
        });

        String cursor = service.createGroupCursor(stream.getId(), "g", "TRIM_HORIZON", null, false);
        StreamingService.ConsumeResult read = service.getMessages(stream.getId(), cursor, 20);

        assertEquals(20, read.messages().size());
        assertEquals(20, loaded.get());
        for (int i = 0; i < 20; i++) {
            assertEquals((long) i, read.messages().get(i).get("offset"));
        }
    }

    private StoredStream activeStream(String name, int partitions) {
        StoredStreamPool pool = service.createStreamPool("pool-" + name, COMPARTMENT, null, null, null, null).pool();
        StoredStream stream = service.createStream(name, partitions, null, null, pool.getId(), null, null).stream();
        when(kafka.isReady(any())).thenReturn(true);
        service.tick();
        return service.getStream(stream.getId());
    }
}
