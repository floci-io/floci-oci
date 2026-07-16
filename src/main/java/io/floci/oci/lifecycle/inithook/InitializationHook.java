package io.floci.oci.lifecycle.inithook;

import java.io.File;
import java.util.List;

public enum InitializationHook {

    BOOT("boot", "boot",
         List.of("/etc/floci-oci/init/boot.d"),
         List.of()),
    START("startup", "start",
          List.of("/etc/floci-oci/init/start.d"),
          List.of()),
    READY("ready", "ready",
          List.of("/etc/floci-oci/init/ready.d"),
          List.of()),
    STOP("shutdown", "shutdown",
         List.of("/etc/floci-oci/init/stop.d", "/etc/floci-oci/init/shutdown.d"),
         List.of());

    private final String name;
    private final String responseKey;
    private final List<File> primaryPaths;
    private final List<File> compatPaths;

    InitializationHook(String name, String responseKey, List<String> primaryPaths, List<String> compatPaths) {
        this.name = name;
        this.responseKey = responseKey;
        this.primaryPaths = primaryPaths.stream().map(File::new).toList();
        this.compatPaths = compatPaths.stream().map(File::new).toList();
    }

    public String getName() {
        return name;
    }

    /** Key used in the {@code /_floci-oci/init} response body. */
    public String getResponseKey() {
        return responseKey;
    }

    /** Floci-native directories for this phase. First occurrence of a filename wins. */
    public List<File> getPrimaryPaths() {
        return primaryPaths;
    }

    /** Legacy-compat directories for this phase (unused). */
    public List<File> getCompatPaths() {
        return compatPaths;
    }
}
