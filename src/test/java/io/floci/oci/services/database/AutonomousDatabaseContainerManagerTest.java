package io.floci.oci.services.database;

import com.github.dockerjava.api.command.HealthState;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.DockerHostResolver;
import io.floci.oci.core.common.docker.ImageCacheService;
import io.floci.oci.core.common.docker.PortAllocator;
import io.floci.oci.core.common.dns.EmbeddedDnsServer;
import io.floci.oci.services.database.model.StoredAutonomousDatabase;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutonomousDatabaseContainerManagerTest {

    private static final String ID = "ocid1.autonomousdatabase.oc1.iad.aaaaaaaaaaaaaaaaaaaaaaabcdefghijklmnop";
    private static final String NAME = "floci-oci-adb-abcdefghijklmnop";
    private static final String IMAGE = "container-registry.oracle.com/database/adb-free:latest-26ai";
    private static final String PASSWORD = "Welcome12345#";

    private EmulatorConfig config;
    private ContainerLifecycleManager lifecycleManager;
    private ImageCacheService imageCacheService;
    private PortAllocator portAllocator;
    private AutonomousDatabaseContainerManager manager;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        lenient().when(config.docker().logMaxSize()).thenReturn("10m");
        lenient().when(config.docker().logMaxFile()).thenReturn("3");
        lenient().when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        lenient().when(config.services().database().image()).thenReturn(IMAGE);
        lenient().when(config.services().database().basePort()).thenReturn(11522);
        lenient().when(config.services().database().maxPort()).thenReturn(11621);
        lifecycleManager = mock(ContainerLifecycleManager.class);
        imageCacheService = mock(ImageCacheService.class);
        portAllocator = mock(PortAllocator.class);
        lenient().when(portAllocator.allocate(11522, 11621)).thenReturn(11530);
        EmbeddedDnsServer embeddedDns = mock(EmbeddedDnsServer.class);
        lenient().when(embeddedDns.getServerIp()).thenReturn(Optional.empty());
        manager = new AutonomousDatabaseContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class), embeddedDns),
                lifecycleManager, imageCacheService, portAllocator, config);
    }

    private StoredAutonomousDatabase database(String workload) {
        StoredAutonomousDatabase database = new StoredAutonomousDatabase();
        database.setId(ID);
        database.setDbName("orders");
        database.setCompartmentId("ocid1.compartment.oc1..c");
        database.setRegion("us-ashburn-1");
        database.getAttributes().put("dbWorkload", workload);
        database.setContainerName(manager.containerName(ID));
        return database;
    }

    private ContainerSpec created() {
        ArgumentCaptor<ContainerSpec> captured = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(captured.capture());
        return captured.getValue();
    }

    private static String env(ContainerSpec spec, String name) {
        return spec.env().stream().filter(entry -> entry.startsWith(name + "="))
                .map(entry -> entry.substring(name.length() + 1)).findFirst().orElse(null);
    }

    @Test
    void theNameFitsADnsLabel() {
        assertEquals(NAME, manager.containerName(ID));
    }

    @Test
    void createRunsTheImageWithCapabilitiesNotPrivilegedAndOnlyTheMtlsPort() {
        StoredAutonomousDatabase database = database("OLTP");

        manager.create(database, PASSWORD);

        ContainerSpec spec = created();
        verify(imageCacheService).ensureImageExists(IMAGE);
        assertEquals(IMAGE, spec.image());
        assertEquals(NAME, spec.name());
        assertEquals(List.of("SYS_ADMIN"), spec.capAdd());
        assertEquals(List.of("/dev/fuse"), spec.devices());
        assertFalse(spec.privileged());
        assertEquals(Map.of(1522, 11530), spec.portBindings());
        assertTrue(spec.mounts().isEmpty(), "the database lives in the container: nothing is mounted");
        assertEquals(11530, database.getHostPort());
    }

    @Test
    void createPassesTheAdminPasswordAndAGeneratedWalletPasswordOnlyToTheContainer() {
        StoredAutonomousDatabase database = database("OLTP");

        manager.create(database, PASSWORD);

        ContainerSpec spec = created();
        assertEquals("ATP", env(spec, "WORKLOAD_TYPE"));
        assertEquals(PASSWORD, env(spec, "ADMIN_PASSWORD"));
        assertEquals("orders", env(spec, "DATABASE_NAME"));
        String walletPassword = env(spec, "WALLET_PASSWORD");
        assertTrue(walletPassword.length() >= 8);
        assertTrue(walletPassword.chars().anyMatch(Character::isLetter));
        assertTrue(walletPassword.chars().anyMatch(Character::isDigit));
        assertFalse(database.toWire().containsValue(walletPassword));
        assertFalse(database.getAttributes().containsValue(PASSWORD));
    }

    @Test
    void eachDatabaseGetsAWalletPasswordOfItsOwn() {
        assertNotEquals(AutonomousDatabaseContainerManager.walletPassword(),
                AutonomousDatabaseContainerManager.walletPassword());
    }

    @Test
    void aDataWarehouseRunsTheAdwWorkload() {
        manager.create(database("DW"), PASSWORD);

        assertEquals("ADW", env(created(), "WORKLOAD_TYPE"));
    }

    @Test
    void theContainerCarriesTheResourceIdentityLabels() {
        manager.create(database("OLTP"), PASSWORD);

        Map<String, String> labels = created().labels();
        assertEquals("database", labels.get(ContainerStorageHelper.SERVICE_LABEL));
        assertEquals(ID, labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL));
        assertEquals("ocid1.compartment.oc1..c", labels.get(ContainerStorageHelper.COMPARTMENT_LABEL));
        assertEquals("us-ashburn-1", labels.get(ContainerStorageHelper.REGION_LABEL));
    }

    @Test
    void aFailedCreateRemovesTheContainerAndReleasesThePort() {
        doThrow(new IllegalStateException("boom")).when(lifecycleManager).createAndStart(any());

        assertThrows(IllegalStateException.class, () -> manager.create(database("OLTP"), PASSWORD));

        verify(lifecycleManager).removeIfExists(NAME);
        verify(portAllocator).release(11530);
    }

    @Test
    void theImageIsPulledBeforeAPortIsClaimed() {
        doThrow(new IllegalStateException("pull failed")).when(imageCacheService).ensureImageExists(IMAGE);

        assertThrows(IllegalStateException.class, () -> manager.create(database("OLTP"), PASSWORD));

        verify(portAllocator, never()).allocate(anyInt(), anyInt());
    }

    @Test
    void stopKeepsTheContainerAndItsPort() {
        StoredAutonomousDatabase database = database("OLTP");
        database.setHostPort(11530);

        manager.stop(database);

        verify(lifecycleManager).stop(NAME, 60);
        verify(lifecycleManager, never()).removeIfExists(any());
        verify(portAllocator, never()).release(anyInt());
    }

    @Test
    void stoppingAContainerThatIsGoneSaysSo() {
        doThrow(new NotFoundException("gone")).when(lifecycleManager).stop(NAME, 60);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> manager.stop(database("OLTP")));

        assertEquals(AutonomousDatabaseService.CONTAINER_GONE, e.getMessage());
    }

    @Test
    void startStartsTheSameContainerAndNeverCreatesOne() {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(inspect.getState().getRunning()).thenReturn(false);
        when(lifecycleManager.inspect(NAME)).thenReturn(Optional.of(inspect));

        manager.start(database("OLTP"));

        verify(lifecycleManager).start(NAME);
        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void startingAContainerThatIsGoneSaysSo() {
        when(lifecycleManager.inspect(NAME)).thenReturn(Optional.empty());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> manager.start(database("OLTP")));

        assertEquals(AutonomousDatabaseService.CONTAINER_GONE, e.getMessage());
        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void restartRestartsTheSameContainer() {
        manager.restart(database("OLTP"));

        verify(lifecycleManager).restart(NAME, 60);
    }

    @Test
    void removeRemovesTheContainerAndReleasesThePort() {
        StoredAutonomousDatabase database = database("OLTP");
        database.setHostPort(11530);

        manager.remove(database);

        verify(lifecycleManager).forceRemove(NAME);
        verify(portAllocator).release(11530);
    }

    @Test
    void aRemovalDockerRefusesPropagatesAndKeepsThePortReserved() {
        doThrow(new DockerException("removal of container is already in progress", 409))
                .when(lifecycleManager).forceRemove(NAME);
        StoredAutonomousDatabase database = database("OLTP");
        database.setHostPort(11530);

        assertThrows(DockerException.class, () -> manager.remove(database));

        verify(portAllocator, never()).release(anyInt());
    }

    @Test
    void adoptionFindsTheContainerByNameAndResourceLabelAndReservesItsPort() {
        adoptable(11530);
        StoredAutonomousDatabase database = database("OLTP");
        database.setHostPort(11530);

        assertTrue(manager.adopt(database));

        verify(portAllocator).markReserved(11530);
        assertEquals(11530, database.getHostPort());
    }

    @Test
    void adoptionRecordsThePublishedPortOfACreateInterruptedBeforeItWasSaved() {
        adoptable(11544);
        StoredAutonomousDatabase database = database("OLTP");

        assertTrue(manager.adopt(database));

        assertEquals(11544, database.getHostPort());
        verify(portAllocator).markReserved(11544);
    }

    @Test
    void adoptionTrustsThePortTheContainerPublishesOverTheStoredOne() {
        adoptable(11544);
        StoredAutonomousDatabase database = database("OLTP");
        database.setHostPort(11530);

        assertTrue(manager.adopt(database));

        assertEquals(11544, database.getHostPort());
        verify(portAllocator).markReserved(11544);
        verify(portAllocator, never()).markReserved(11530);
    }

    @Test
    void theBoundPortIsTheFallbackWhenTheContainerHasNoConfiguredBinding() {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(inspect.getHostConfig().getPortBindings()).thenReturn(new Ports());
        when(inspect.getNetworkSettings().getPorts()).thenReturn(bound(11550));

        assertEquals(11550, AutonomousDatabaseContainerManager.publishedHostPort(inspect));
    }

    @Test
    void aContainerThatVanishesBeforeItsInspectIsNotAdopted() {
        Container container = mock(Container.class);
        when(container.getLabels()).thenReturn(Map.of(ContainerStorageHelper.RESOURCE_ID_LABEL, ID));
        when(lifecycleManager.findByName(NAME)).thenReturn(Optional.of(container));
        when(lifecycleManager.inspect(NAME)).thenReturn(Optional.empty());

        assertFalse(manager.adopt(database("OLTP")));

        verify(portAllocator, never()).markReserved(anyInt());
    }

    private void adoptable(int publishedPort) {
        Container container = mock(Container.class);
        when(container.getLabels()).thenReturn(Map.of(ContainerStorageHelper.RESOURCE_ID_LABEL, ID));
        when(lifecycleManager.findByName(NAME)).thenReturn(Optional.of(container));
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(inspect.getHostConfig().getPortBindings()).thenReturn(bound(publishedPort));
        when(lifecycleManager.inspect(NAME)).thenReturn(Optional.of(inspect));
    }

    private static Ports bound(int hostPort) {
        Ports ports = new Ports();
        ports.bind(ExposedPort.tcp(1522), Ports.Binding.bindPort(hostPort));
        return ports;
    }

    @Test
    void aContainerOfAnotherResourceIsNotAdopted() {
        Container container = mock(Container.class);
        when(container.getLabels()).thenReturn(Map.of(ContainerStorageHelper.RESOURCE_ID_LABEL, "ocid1.other"));
        when(lifecycleManager.findByName(NAME)).thenReturn(Optional.of(container));

        assertFalse(manager.adopt(database("OLTP")));

        verify(portAllocator, never()).markReserved(anyInt());
    }

    @Test
    void aMissingContainerIsNotAdopted() {
        when(lifecycleManager.findByName(NAME)).thenReturn(Optional.empty());

        assertFalse(manager.adopt(database("OLTP")));
    }

    @Test
    void healthFollowsTheImageHealthCheck() {
        assertEquals(AutonomousDatabaseContainerManager.Health.MISSING, manager.health(database("OLTP")));
        assertEquals(AutonomousDatabaseContainerManager.Health.EXITED, healthOf(false, null));
        assertEquals(AutonomousDatabaseContainerManager.Health.STARTING, healthOf(true, "starting"));
        assertEquals(AutonomousDatabaseContainerManager.Health.UNHEALTHY, healthOf(true, "unhealthy"));
        assertEquals(AutonomousDatabaseContainerManager.Health.HEALTHY, healthOf(true, "healthy"));
    }

    private AutonomousDatabaseContainerManager.Health healthOf(boolean running, String status) {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(inspect.getState().getRunning()).thenReturn(running);
        HealthState health = status == null ? null : mock(HealthState.class);
        if (health != null) {
            when(health.getStatus()).thenReturn(status);
        }
        when(inspect.getState().getHealth()).thenReturn(health);
        when(lifecycleManager.inspect(NAME)).thenReturn(Optional.of(inspect));
        return manager.health(database("OLTP"));
    }

    @Test
    void theWalletDirectoryIsReadByFileName() throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(tar)) {
            out.putArchiveEntry(new TarArchiveEntry("tls_wallet/"));
            out.closeArchiveEntry();
            byte[] content = "myatp_high = (description=)".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry("tls_wallet/tnsnames.ora");
            entry.setSize(content.length);
            out.putArchiveEntry(entry);
            out.write(content);
            out.closeArchiveEntry();
        }

        Map<String, byte[]> files = AutonomousDatabaseContainerManager.untar(new ByteArrayInputStream(tar.toByteArray()));

        assertEquals(List.of("tnsnames.ora"), List.copyOf(files.keySet()));
        assertEquals("myatp_high = (description=)", new String(files.get("tnsnames.ora"), StandardCharsets.UTF_8));
    }
}
