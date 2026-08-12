package org.floci.core.infra;

import java.security.Security;
import java.util.logging.Logger;

/**
 * Utility for security provider initialization across emulator runtimes.
 */
public class CryptoUtils {
    private static final Logger LOGGER = Logger.getLogger(CryptoUtils.class.getName());
    private static boolean initialized = false;

    public static synchronized void initializeSecurityProviders() {
        if (!initialized) {
            try {
                // Register Bouncy Castle if present on classpath
                Class<?> bcProviderClass = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider");
                java.security.Provider provider = (java.security.Provider) bcProviderClass.getDeclaredConstructor().newInstance();
                if (Security.getProvider(provider.getName()) == null) {
                    Security.addProvider(provider);
                    LOGGER.info("Registered security provider: " + provider.getName());
                }
            } catch (ClassNotFoundException e) {
                LOGGER.config("BouncyCastleProvider not on classpath; using default JDK security providers.");
            } catch (Exception e) {
                LOGGER.warning("Could not initialize security provider: " + e.getMessage());
            }
            initialized = true;
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
