package io.floci.oci.services.artifacts;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.services.artifacts.model.StoredContainerRepository;
import io.floci.oci.services.objectstorage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class ArtifactsServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..artifactstest";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..artifactstest";

    private ArtifactsService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.services().artifacts().mock()).thenReturn(true);
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
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

    @Test
    void registryHostNamesTheRegion() {
        assertEquals("us-ashburn-1", OcirRegistryResource.regionOf("iad.ocir.localhost.floci.io:4599").orElseThrow());
        assertEquals("us-phoenix-1", OcirRegistryResource.regionOf("us-phoenix-1.ocir.io").orElseThrow());
        assertEquals("us-ashburn-1", OcirRegistryResource.regionOf("ocir.us-ashburn-1.oci.oraclecloud.com")
                .orElseThrow());
        assertTrue(OcirRegistryResource.regionOf("localhost:4599").isEmpty());
    }

    @Test
    void uploadLocationLosesTheBackendPrefix() {
        assertEquals("/v2/ns/app/blobs/uploads/123?_state=x", OcirRegistryResource.clientLocation(
                "http://localhost:5200/v2/iad/ns/app/blobs/uploads/123?_state=x", "iad/ns/app", "ns/app"));
    }
}
