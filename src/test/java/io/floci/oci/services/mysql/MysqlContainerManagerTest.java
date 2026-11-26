package io.floci.oci.services.mysql;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.ExecResult;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.DockerHostResolver;
import io.floci.oci.core.common.docker.ImageCacheService;
import io.floci.oci.core.common.docker.PortAllocator;
import io.floci.oci.services.mysql.model.StoredMysqlDbSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MysqlContainerManagerTest {

    private static final String ID = "ocid1.mysqldbsystem.oc1.iad.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabcdefghijklmnop";
    private static final String PASSWORD = "BEstrO0ng_#11";

    private EmulatorConfig config;
    private ContainerLifecycleManager lifecycleManager;
    private ImageCacheService imageCacheService;
    private PortAllocator portAllocator;
    private MysqlContainerManager manager;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        lenient().when(config.docker().logMaxSize()).thenReturn("10m");
        lenient().when(config.docker().logMaxFile()).thenReturn("3");
        lenient().when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        lenient().when(config.services().mysql().image()).thenReturn("mysql:{version}");
        lenient().when(config.services().mysql().basePort()).thenReturn(13306);
        lenient().when(config.services().mysql().maxPort()).thenReturn(13405);
        lenient().when(config.storage().mode()).thenReturn("persistent");
        lifecycleManager = mock(ContainerLifecycleManager.class);
        imageCacheService = mock(ImageCacheService.class);
        portAllocator = mock(PortAllocator.class);
        lenient().when(portAllocator.allocate(13306, 13405)).thenReturn(13310);
        manager = new MysqlContainerManager(new ContainerBuilder(config, mock(DockerHostResolver.class), null),
                lifecycleManager, imageCacheService, portAllocator, config);
    }

    private StoredMysqlDbSystem system(String adminUsername) {
        StoredMysqlDbSystem system = new StoredMysqlDbSystem();
        system.setId(ID);
        system.setMysqlVersion("8.4.4");
        system.setCompartmentId("ocid1.compartment.oc1..c");
        system.setRegion("us-ashburn-1");
        system.setAdminUsername(adminUsername);
        system.setContainerName(manager.containerName(ID));
        system.setVolumeName(manager.volumeName(ID));
        return system;
    }

    private ContainerSpec started() {
        ArgumentCaptor<ContainerSpec> captured = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(captured.capture());
        return captured.getValue();
    }

    private static String env(ContainerSpec spec, String name) {
        return spec.env().stream().filter(entry -> entry.startsWith(name + "="))
                .map(entry -> entry.substring(name.length() + 1)).findFirst().orElse(null);
    }

    @Test
    void namesFitADnsLabelAndStayUniquePerSystem() {
        assertEquals("floci-oci-mysql-abcdefghijklmnop", manager.containerName(ID));
        assertEquals("floci-oci-mysql-vol-abcdefghijklmnop", manager.volumeName(ID));
    }

    @Test
    void startRunsTheRequestedVersionCreatesTheAdminAndRecordsThePort() {
        StoredMysqlDbSystem system = system("adminUser");

        manager.start(system, PASSWORD);

        ContainerSpec spec = started();
        assertEquals("mysql:8.4.4", spec.image());
        assertEquals("floci-oci-mysql-abcdefghijklmnop", spec.name());
        assertEquals(List.of("sh", "-c", MysqlContainerManager.ENTRYPOINT), spec.entrypoint());
        assertEquals(MysqlContainerManager.createAdminSql("adminUser", PASSWORD), env(spec, "FLOCI_INIT_SQL"));
        assertFalse(env(spec, "MYSQL_ROOT_PASSWORD").isEmpty());
        assertFalse(PASSWORD.equals(env(spec, "MYSQL_ROOT_PASSWORD")), "root gets a password of its own");
        verify(imageCacheService).ensureImageExists("mysql:8.4.4");
        verify(lifecycleManager).ensureVolume("floci-oci-mysql-vol-abcdefghijklmnop");
        assertEquals(13310, system.getHostPort());
    }

    @Test
    void aRestartKeepsThePreviousPortWhenItCanBeReclaimed() {
        StoredMysqlDbSystem system = system("adminUser");
        system.setHostPort(13320);
        when(portAllocator.reclaim(13320)).thenReturn(true);

        manager.start(system, null);

        assertEquals(13320, system.getHostPort());
        verify(portAllocator, never()).allocate(13306, 13405);
    }

    @Test
    void aRestartTakesANewPortWhenThePreviousOneIsTaken() {
        StoredMysqlDbSystem system = system("adminUser");
        system.setHostPort(13320);
        when(portAllocator.reclaim(13320)).thenReturn(false);

        manager.start(system, null);

        assertEquals(13310, system.getHostPort());
        verify(portAllocator, never()).markReserved(13320);
    }

    @Test
    void anAdminNamedRootIsRootItself() {
        manager.start(system("root"), PASSWORD);

        ContainerSpec spec = started();
        assertEquals(PASSWORD, env(spec, "MYSQL_ROOT_PASSWORD"));
        assertEquals("", env(spec, "FLOCI_INIT_SQL"));
    }

    @Test
    void aRestartWithoutAPasswordCreatesNoAdmin() {
        manager.start(system("adminUser"), null);

        assertEquals("", env(started(), "FLOCI_INIT_SQL"));
    }

    @Test
    void theAdminStatementEscapesQuotesAndBackslashes() {
        assertEquals("CREATE USER 'o''brien'@'%' IDENTIFIED BY 'a\\\\b''c';\n"
                        + "GRANT ALL PRIVILEGES ON *.* TO 'o''brien'@'%' WITH GRANT OPTION;",
                MysqlContainerManager.createAdminSql("o'brien", "a\\b'c"));
    }

    @Test
    void aFailedStartRemovesTheContainerAndReleasesThePort() {
        doThrow(new IllegalStateException("boom")).when(lifecycleManager).createAndStart(any());

        assertThrows(IllegalStateException.class, () -> manager.start(system("adminUser"), PASSWORD));

        verify(portAllocator).release(13310);
    }

    @Test
    void theProbeIsAPingOverTcp() {
        when(lifecycleManager.exec(eq("floci-oci-mysql-abcdefghijklmnop"), anyList(), anyList(), any()))
                .thenReturn(new ExecResult(0, "", ""));

        assertTrue(manager.probeReady(system("adminUser")));

        verify(lifecycleManager).exec(eq("floci-oci-mysql-abcdefghijklmnop"), eq(List.of()),
                eq(List.of("mysqladmin", "ping", "-h", "127.0.0.1", "-P", "3306", "--silent")), any());
    }

    @Test
    void stopReleasesThePortAndKeepsTheVolume() {
        StoredMysqlDbSystem system = system("adminUser");
        system.setHostPort(13310);

        manager.stop(system);

        verify(lifecycleManager).removeIfExists("floci-oci-mysql-abcdefghijklmnop");
        verify(portAllocator).release(13310);
        verify(lifecycleManager, never()).removeVolume(any());
    }

    @Test
    void purgeAlwaysRemovesTheVolume() {
        manager.purge(system("adminUser"));

        verify(lifecycleManager).removeVolume("floci-oci-mysql-vol-abcdefghijklmnop");
    }
}
