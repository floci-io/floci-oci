package io.floci.oci.config;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.config.WithDefault;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against the sibling-repo failure mode where {@code @WithDefault} annotations and
 * {@code application.yml} silently disagree, so code readers and the running emulator see
 * different defaults. The yml is the effective source of truth; the annotations must match it.
 */
@QuarkusTest
class ApplicationDefaultsTest {

    @Inject
    EmulatorConfig config;

    @Test
    void effectiveDefaultsMatchAnnotationDefaults() throws Exception {
        assertEquals(annotationDefault(EmulatorConfig.class, "port"), String.valueOf(config.port()));
        assertEquals(annotationDefault(EmulatorConfig.class, "defaultRegion"), config.defaultRegion());
        assertEquals(annotationDefault(EmulatorConfig.class, "defaultRealm"), config.defaultRealm());
        assertEquals(annotationDefault(EmulatorConfig.class, "defaultNamespace"), "floci-local");
        assertEquals(annotationDefault(EmulatorConfig.StorageConfig.class, "mode"),
                config.storage().mode());
        assertEquals(annotationDefault(EmulatorConfig.AuthConfig.class, "requireSignature"),
                String.valueOf(config.auth().requireSignature()));
        assertEquals(annotationDefault(EmulatorConfig.TlsConfig.class, "enabled"),
                String.valueOf(config.tls().enabled()));
    }

    @Test
    void pilotServicesAreEnabledByDefault() {
        assertTrue(config.services().identity().enabled());
        assertTrue(config.services().objectstorage().enabled());
    }

    @Test
    void signatureIsNotRequiredByDefault() {
        assertFalse(config.auth().requireSignature());
    }

    private static String annotationDefault(Class<?> iface, String method) throws Exception {
        Method m = iface.getDeclaredMethod(method);
        WithDefault annotation = m.getAnnotation(WithDefault.class);
        return annotation.value();
    }
}
