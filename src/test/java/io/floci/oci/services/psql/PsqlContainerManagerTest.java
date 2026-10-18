package io.floci.oci.services.psql;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.ExecResult;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.DockerHostResolver;
import io.floci.oci.core.common.docker.ImageCacheService;
import io.floci.oci.core.common.docker.PortAllocator;
import io.floci.oci.services.psql.model.StoredDbSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PsqlContainerManagerTest {

    private static final String ID = "ocid1.postgresqldbsystem.oc1.iad.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabcdefghijklmnop";
    private static final String PASSWORD = "BEstrO0ng_#11";

    private EmulatorConfig config;
    private ContainerLifecycleManager lifecycleManager;
    private ImageCacheService imageCacheService;
    private PortAllocator portAllocator;
    private PsqlContainerManager manager;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        lenient().when(config.docker().logMaxSize()).thenReturn("10m");
        lenient().when(config.docker().logMaxFile()).thenReturn("3");
        lenient().when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        lenient().when(config.services().psql().image()).thenReturn("postgres:{major}-alpine");
        lenient().when(config.services().psql().basePort()).thenReturn(15432);
        lenient().when(config.services().psql().maxPort()).thenReturn(15531);
        lenient().when(config.storage().mode()).thenReturn("persistent");
        lifecycleManager = mock(ContainerLifecycleManager.class);
        imageCacheService = mock(ImageCacheService.class);
        portAllocator = mock(PortAllocator.class);
        lenient().when(portAllocator.allocate(15432, 15531)).thenReturn(15440);
        manager = new PsqlContainerManager(new ContainerBuilder(config, mock(DockerHostResolver.class), null),
                lifecycleManager, imageCacheService, portAllocator, config);
    }

    private StoredDbSystem system(String dbVersion) {
        StoredDbSystem system = new StoredDbSystem();
        system.setId(ID);
        system.setDbVersion(dbVersion);
        system.setCompartmentId("ocid1.compartment.oc1..c");
        system.setRegion("us-phoenix-1");
        system.setAdminUsername("adminUser");
        system.setContainerName(manager.containerName(ID));
        system.setVolumeName(manager.volumeName(ID));
        system.setCertificatePem("CERT");
        system.setPrivateKeyPem("KEY");
        return system;
    }

    @Test
    void namesFitADnsLabelAndStayUniquePerSystem() {
        assertEquals("floci-oci-psql-abcdefghijklmnop", manager.containerName(ID));
        assertEquals("floci-oci-psql-vol-abcdefghijklmnop", manager.volumeName(ID));
        assertTrue(manager.containerName(ID).length() <= 63);
    }

    @Test
    void startRunsTheRequestedMajorWithTlsAndRecordsThePort() {
        StoredDbSystem system = system("16");

        manager.start(system, PASSWORD);

        ArgumentCaptor<ContainerSpec> captured = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(captured.capture());
        ContainerSpec spec = captured.getValue();
        assertEquals("postgres:16-alpine", spec.image());
        assertEquals("floci-oci-psql-abcdefghijklmnop", spec.name());
        assertTrue(spec.env().containsAll(List.of("POSTGRES_USER=adminUser", "POSTGRES_PASSWORD=" + PASSWORD,
                "POSTGRES_DB=postgres", "FLOCI_TLS_CERT=CERT", "FLOCI_TLS_KEY=KEY")), spec.env().toString());
        assertEquals(15440, spec.portBindings().get(5432));
        assertEquals(List.of("sh", "-c"), spec.entrypoint().subList(0, 2));
        assertTrue(spec.entrypoint().get(2).contains("exec docker-entrypoint.sh postgres -c ssl=on"));
        assertEquals("psql", spec.labels().get("io.floci.service"));
        assertEquals(ID, spec.labels().get("io.floci.resource-id"));
        assertEquals("us-phoenix-1", spec.labels().get("io.floci.region"));
        assertEquals(15440, system.getHostPort());
        verify(lifecycleManager).ensureVolume("floci-oci-psql-vol-abcdefghijklmnop");
    }

    @Test
    void theImageIsPulledBeforeAPortIsClaimed() {
        manager.start(system("15"), PASSWORD);

        InOrder order = inOrder(imageCacheService, portAllocator);
        order.verify(imageCacheService).ensureImageExists("postgres:15-alpine");
        order.verify(portAllocator).allocate(15432, 15531);
    }

    @Test
    void aSystemKeepsItsPortAcrossARestartWhenItIsStillFree() {
        StoredDbSystem system = system("16");
        system.setHostPort(15450);
        when(portAllocator.isPortFree(15450)).thenReturn(true);

        manager.start(system, PASSWORD);

        verify(portAllocator).markReserved(15450);
        verify(portAllocator, never()).allocate(anyInt(), anyInt());
        assertEquals(15450, system.getHostPort());
    }

    @Test
    void aFailedStartLeavesNoContainerAndNoClaimedPort() {
        when(lifecycleManager.createAndStart(any())).thenThrow(new IllegalStateException("port is already allocated"));
        StoredDbSystem system = system("16");

        assertThrows(IllegalStateException.class, () -> manager.start(system, PASSWORD));

        verify(portAllocator).release(15440);
        verify(lifecycleManager, times(2)).removeIfExists("floci-oci-psql-abcdefghijklmnop");
        assertEquals(0, system.getHostPort());
    }

    @Test
    void stopReleasesThePortAndKeepsTheVolume() {
        StoredDbSystem system = system("16");
        system.setHostPort(15440);

        manager.stop(system);

        verify(lifecycleManager).removeIfExists("floci-oci-psql-abcdefghijklmnop");
        verify(portAllocator).release(15440);
        verify(lifecycleManager, never()).removeVolume(any());
    }

    @Test
    void removeDropsTheVolumeOnlyUnderTheSharedVolumePolicy() {
        manager.remove(system("16"));
        verify(lifecycleManager, never()).removeVolume(any());

        when(config.storage().mode()).thenReturn("memory");
        manager.remove(system("16"));
        verify(lifecycleManager).removeVolume("floci-oci-psql-vol-abcdefghijklmnop");
    }

    @Test
    void purgeDropsTheVolumeWhateverThePolicy() {
        manager.purge(system("16"));

        verify(lifecycleManager).removeIfExists(any());
        verify(lifecycleManager).removeVolume("floci-oci-psql-vol-abcdefghijklmnop");
    }

    @Test
    void readinessIsProbedOverTcpLoopback() {
        when(lifecycleManager.exec(any(), anyList(), anyList(), any())).thenReturn(new ExecResult(0, "", ""));

        assertTrue(manager.probeReady(system("16")));

        verify(lifecycleManager).exec(eq("floci-oci-psql-abcdefghijklmnop"), eq(List.of()),
                eq(List.of("pg_isready", "-h", "127.0.0.1", "-p", "5432", "-U", "adminUser", "-d", "postgres")),
                any());
    }

    @Test
    void theServerVersionIsReadFromPostgres() {
        when(lifecycleManager.exec(any(), anyList(), anyList(), any()))
                .thenReturn(new ExecResult(0, "16.4 (Debian 16.4-1.pgdg120+1)\n", ""));
        assertEquals(Optional.of("16.4"), manager.serverVersion(system("16")));

        when(lifecycleManager.exec(any(), anyList(), anyList(), any())).thenReturn(new ExecResult(2, "", "down"));
        assertEquals(Optional.empty(), manager.serverVersion(system("16")));
    }

    @Test
    void thePasswordTravelsAsAVariableAndNeverShowsInAnError() {
        when(lifecycleManager.exec(any(), anyList(), anyList(), any()))
                .thenReturn(new ExecResult(3, "", "ERROR: could not set N3w'pass"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> manager.resetPassword(system("16"), "N3w'pass"));

        assertFalse(e.getMessage().contains("N3w'pass"), e.getMessage());
        ArgumentCaptor<List<String>> env = ArgumentCaptor.captor();
        ArgumentCaptor<List<String>> command = ArgumentCaptor.captor();
        verify(lifecycleManager).exec(any(), env.capture(), command.capture(), any());
        assertTrue(env.getValue().contains("FLOCI_PASSWORD=N3w'pass"));
        assertFalse(String.join(" ", command.getValue()).contains("N3w'pass"), "not interpolated into the command");
    }

    @Test
    void theDataDirectoryFollowsTheImageLayout() {
        assertEquals("/var/lib/postgresql/data", PsqlContainerManager.dataPath("17"));
        assertEquals("/var/lib/postgresql", PsqlContainerManager.dataPath("18"));
    }
}
