package io.floci.oci.services.artifacts;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.ContainerImageResolver.RegistryMirror;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.services.artifacts.model.StoredContainerRepository;
import io.floci.oci.services.objectstorage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactsServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..artifactstest";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..artifactstest";

    private ArtifactsService service;
    private EmulatorConfig config;
    private ObjectStorageService objectStorage;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.services().artifacts().mock()).thenReturn(true);
        lenient().when(config.services().artifacts().enabled()).thenReturn(true);
        objectStorage = mock(ObjectStorageService.class);
        lenient().when(objectStorage.namespaceOf(anyString())).thenReturn("testns");
        service = new ArtifactsService(new InMemoryStorage<>(), new InMemoryStorage<>(), config, objectStorage);
    }

    private StoredContainerRepository create(String name) {
        return service.createRepository(Map.of("compartmentId", COMPARTMENT, "displayName", name));
    }

    @Test
    void createIsPrivateMutableAndAvailable() {
        StoredContainerRepository r = create("project/app");
        assertTrue(r.getId().startsWith("ocid1.containerrepo.oc1.iad."));
        assertEquals("AVAILABLE", r.getLifecycleState());
        assertEquals("testns", r.getNamespace());
        assertFalse(r.isPublicRepository());
        assertFalse(r.isImmutable());
        assertNull(r.getReadme());
    }

    @Test
    void invalidNameIsInvalidParameter() {
        OciException e = assertThrows(OciException.class, () -> create("Project/App"));
        assertEquals("InvalidParameter", e.getCode());
    }

    @Test
    void duplicateNameInTheRegionIsRejected() {
        create("app");
        OciException e = assertThrows(OciException.class, () -> service.createRepository(Map.of(
                "compartmentId", "ocid1.compartment.oc1..other", "displayName", "app")));
        assertEquals(409, e.getHttpStatus());
        assertEquals("NotAuthorizedOrResourceAlreadyExists", e.getCode());
    }

    @Test
    void readmeNeedsAKnownFormat() {
        assertThrows(OciException.class, () -> service.createRepository(Map.of("compartmentId", COMPARTMENT,
                "displayName", "app", "readme", Map.of("content", "# hi", "format", "HTML"))));
        StoredContainerRepository r = service.createRepository(Map.of("compartmentId", COMPARTMENT,
                "displayName", "app", "readme", Map.of("content", "# hi", "format", "TEXT_MARKDOWN")));
        assertEquals("TEXT_MARKDOWN", r.getReadme().get("format"));
    }

    @Test
    void updateAndDelete() {
        StoredContainerRepository r = create("app");
        StoredContainerRepository updated = service.updateRepository(r.getId(),
                Map.of("isPublic", true, "isImmutable", true), null);
        assertTrue(updated.isPublicRepository());
        assertTrue(updated.isImmutable());
        service.deleteRepository(r.getId(), null);
        assertThrows(OciException.class, () -> service.getRepository(r.getId()));
    }

    @Test
    void listFiltersByCompartmentAndName() {
        create("a");
        create("b");
        assertEquals(List.of("a", "b"), service.listRepositories(COMPARTMENT, false, null, null, null, null,
                "DISPLAYNAME", null).stream().map(StoredContainerRepository::getDisplayName).toList());
        assertEquals(1, service.listRepositories(COMPARTMENT, false, null, "b", null, null, null, null).size());
        assertTrue(service.listRepositories("ocid1.compartment.oc1..other", false, null, null, null, null, null,
                null).isEmpty());
    }

    @Test
    void configurationDefaultsToNoCreateOnPush() {
        Map<String, Object> configuration = service.configuration();
        assertEquals(false, configuration.get("isRepositoryCreatedOnFirstPush"));
        assertEquals("testns", configuration.get("namespace"));
    }

    @Test
    void pushIntoAnUnknownRepositoryCreatesItOnlyWhenConfigured() {
        assertTrue(service.repositoryForPush("new/app").isEmpty());
        service.updateConfiguration(Map.of("isRepositoryCreatedOnFirstPush", true));
        StoredContainerRepository created = service.repositoryForPush("new/app").orElseThrow();
        assertEquals(TENANCY, created.getCompartmentId());
        assertFalse(created.isPublicRepository());
    }

    @Test
    void defaultNamespaceResolvesToTheDefaultTenancy() {
        assertEquals(TENANCY, service.tenancyOfNamespace("testns").orElseThrow());
    }

    @Test
    void imageIdIsStablePerRepositoryAndDigest() {
        StoredContainerRepository r = create("app");
        String id = service.imageId(r, "sha256:abc");
        assertEquals(id, service.imageId(r, "sha256:abc"));
        assertNotEquals(id, service.imageId(r, "sha256:def"));
        assertTrue(id.startsWith("ocid1.containerimage.oc1.iad."));
    }

    @Test
    void mockModeListsNoImages() {
        StoredContainerRepository r = create("app");
        assertTrue(service.images(r).isEmpty());
        assertEquals(0, service.stats(r).imageCount());
    }

    @Test
    void backendPathIsPrefixedByRegionAndNamespace() {
        assertEquals("iad/testns/project/app", service.backendRepository(create("project/app")));
    }

    private ArtifactsService withRegistry(ArtifactsRegistryManager registry) {
        when(config.services().artifacts().mock()).thenReturn(false);
        return new ArtifactsService(new InMemoryStorage<>(), new InMemoryStorage<>(), config, objectStorage, registry);
    }

    @Test
    void localOcirImagesArePulledFromThePublishedPort() {
        ArtifactsRegistryManager registry = mock(ArtifactsRegistryManager.class);
        when(registry.hostPort()).thenReturn(5201);
        ArtifactsService real = withRegistry(registry);

        assertEquals("localhost:5201/iad/testns/project/app:1.0",
                real.pullable("iad.ocir.localhost.floci.io:4599/testns/project/app:1.0"));
        assertEquals("localhost:5201/phx/testns/app@sha256:abc",
                real.pullable("us-phoenix-1.ocir.io/testns/app@sha256:abc"));
        verify(registry, times(2)).ensureReady();
    }

    @Test
    void imagesThisRegistryDoesNotOwnAreUnchanged() {
        ArtifactsRegistryManager registry = mock(ArtifactsRegistryManager.class);
        ArtifactsService real = withRegistry(registry);

        assertEquals("iad.ocir.io/otherns/app:1", real.pullable("iad.ocir.io/otherns/app:1"));
        assertEquals("docker.io/library/alpine:3", real.pullable("docker.io/library/alpine:3"));
        assertEquals("alpine", real.pullable("alpine"));
        assertEquals("iad.ocir.io/testns", real.pullable("iad.ocir.io/testns"));
        assertNull(real.pullable(null));
        verify(registry, never()).ensureReady();
    }

    @Test
    void mockOrDisabledArtifactsLeaveImagesUnchanged() {
        assertEquals("iad.ocir.io/testns/app:1", service.pullable("iad.ocir.io/testns/app:1"));
        ArtifactsService real = withRegistry(mock(ArtifactsRegistryManager.class));
        when(config.services().artifacts().enabled()).thenReturn(false);
        assertEquals("iad.ocir.io/testns/app:1", real.pullable("iad.ocir.io/testns/app:1"));
    }

    @Test
    void registryMirrorRoutesEveryLocalOcirHostOfTheRegionToTheSidecar() {
        ArtifactsRegistryManager registry = mock(ArtifactsRegistryManager.class);
        when(registry.sidecarEndpoint()).thenReturn("host.docker.internal:5201");
        when(config.port()).thenReturn(4599);
        ArtifactsService real = withRegistry(registry);

        RegistryMirror mirror = real.registryMirror("us-ashburn-1").orElseThrow();

        assertEquals(List.of(
                "iad.ocir.localhost.floci.io", "iad.ocir.localhost.floci.io:4599",
                "us-ashburn-1.ocir.localhost.floci.io", "us-ashburn-1.ocir.localhost.floci.io:4599",
                "ocir.us-ashburn-1.oci.localhost.floci.io", "ocir.us-ashburn-1.oci.localhost.floci.io:4599"),
                mirror.hosts());
        assertEquals("http://host.docker.internal:5201", mirror.endpoint());
        assertEquals("iad/", mirror.pathPrefix());
        for (String host : mirror.hosts()) {
            assertEquals("us-ashburn-1", OcirHosts.regionOf(host).orElseThrow(), host);
        }
        verify(registry).ensureStarted();
        verify(registry, never()).ensureReady();
    }

    @Test
    void registryMirrorAlsoCoversTheConfiguredHostname() {
        ArtifactsRegistryManager registry = mock(ArtifactsRegistryManager.class);
        when(registry.sidecarEndpoint()).thenReturn("172.18.0.4:5000");
        when(config.port()).thenReturn(4599);
        when(config.hostname()).thenReturn(Optional.of("floci-oci"));
        ArtifactsService real = withRegistry(registry);

        RegistryMirror mirror = real.registryMirror("us-phoenix-1").orElseThrow();

        assertTrue(mirror.hosts().contains("phx.ocir.floci-oci:4599"));
        assertTrue(mirror.hosts().contains("phx.ocir.localhost.floci.io"));
        assertEquals(12, mirror.hosts().size());
        assertEquals("http://172.18.0.4:5000", mirror.endpoint());
        assertEquals("phx/", mirror.pathPrefix());
    }

    @Test
    void mockOrDisabledArtifactsOfferNoRegistryMirror() {
        assertTrue(service.registryMirror("us-ashburn-1").isEmpty());
        ArtifactsRegistryManager registry = mock(ArtifactsRegistryManager.class);
        ArtifactsService real = withRegistry(registry);
        when(config.services().artifacts().enabled()).thenReturn(false);
        assertTrue(real.registryMirror("us-ashburn-1").isEmpty());
        verify(registry, never()).ensureStarted();
    }

    @Test
    void registryHostNamesTheRegion() {
        assertEquals("us-ashburn-1", OcirHosts.regionOf("iad.ocir.localhost.floci.io:4599").orElseThrow());
        assertEquals("us-phoenix-1", OcirHosts.regionOf("us-phoenix-1.ocir.io").orElseThrow());
        assertEquals("us-ashburn-1", OcirHosts.regionOf("ocir.us-ashburn-1.oci.oraclecloud.com")
                .orElseThrow());
        assertTrue(OcirHosts.regionOf("localhost:4599").isEmpty());
    }

    @Test
    void repositoryComponentsNamedAfterOperationsStayInTheName() {
        assertOperation("ns/blobs/manifests/v1", "ns", "blobs", "manifests", "/v1");
        assertOperation("ns/project/blobs/app/manifests/v1", "ns", "project/blobs/app", "manifests", "/v1");
        assertOperation("ns/tags/tags/list", "ns", "tags", "tags", "/list");
        assertOperation("ns/manifests/blobs/sha256:abc123", "ns", "manifests", "blobs", "/sha256:abc123");
        assertOperation("ns/app/blobs/uploads/", "ns", "app", "blobs", "/uploads/");
        assertOperation("ns/app/blobs/uploads", "ns", "app", "blobs", "/uploads");
        assertOperation("ns/app/manifests/blobs/uploads/123", "ns", "app/manifests", "blobs", "/uploads/123");
        assertTrue(OcirRegistryResource.RepositoryOperation.parse("ns/manifests/v1").isEmpty());
        assertTrue(OcirRegistryResource.RepositoryOperation.parse("_catalog").isEmpty());
    }

    private static void assertOperation(String path, String namespace, String repository, String kind,
                                        String suffix) {
        assertEquals(new OcirRegistryResource.RepositoryOperation(namespace, repository, kind, suffix),
                OcirRegistryResource.RepositoryOperation.parse(path).orElseThrow(), path);
    }

    @Test
    void uploadLocationLosesTheBackendPrefix() {
        assertEquals("/v2/ns/app/blobs/uploads/123?_state=x", OcirRegistryResource.clientLocation(
                "http://localhost:5200/v2/iad/ns/app/blobs/uploads/123?_state=x", "iad/ns/app", "ns/app"));
    }

    @Test
    void anAllowedMountSourceIsTranslatedToTheBackendRepository() {
        assertEquals("mount=sha256%3Aabc&from=iad%2Fns%2Fapp", OcirRegistryResource.mountQuery(
                "mount=sha256:abc&from=ns/app", from -> Optional.of("iad/" + from)));
        assertEquals("x=1&mount=sha256%3Aabc&from=iad%2Fns%2Fapp", OcirRegistryResource.mountQuery(
                "x=1&from=ns%2Fapp&mount=sha256:abc&from=other/app", from -> Optional.of("iad/" + from)));
    }

    @Test
    void aRefusedMountSourceLeavesAPlainUploadSession() {
        assertNull(OcirRegistryResource.mountQuery("mount=sha256:abc&from=iad/other/app", from -> Optional.empty()));
        assertEquals("x=1", OcirRegistryResource.mountQuery("x=1&mount=sha256:abc", from -> Optional.of(from)));
        assertEquals("digest=sha256:abc", OcirRegistryResource.mountQuery("digest=sha256:abc",
                from -> Optional.of(from)));
    }
}
