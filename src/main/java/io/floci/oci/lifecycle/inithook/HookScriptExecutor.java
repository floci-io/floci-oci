package io.floci.oci.lifecycle.inithook;

import io.floci.oci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class HookScriptExecutor {

    private static final Logger LOG = Logger.getLogger(HookScriptExecutor.class);
    private final EmulatorConfig.InitHooksConfig initHooksConfig;

    @Inject
    public HookScriptExecutor(EmulatorConfig emulatorConfig) {
        this.initHooksConfig = emulatorConfig.initHooks();
    }

    public void run(File scriptFile) throws IOException, InterruptedException {
        run(scriptFile.getParentFile(), scriptFile.getName());
    }

    public void run(File hookDirectory, String scriptFileName) throws IOException, InterruptedException {
        String command = scriptFileName.endsWith(".py") ? "python3" : initHooksConfig.shellExecutable();
        LOG.debugv("Executing hook script {0} via {1}", scriptFileName, command);

        // Inherit parent I/O so script output is streamed directly and does not block on unconsumed buffers.
        Process process = new ProcessBuilder(command, scriptFileName).directory(hookDirectory).inheritIO().start();
        run(process, scriptFileName);
    }

    void run(Process process, String scriptFileName) throws InterruptedException {
        int exitCode = waitForProcessExitCode(process, scriptFileName);
        if (exitCode != 0) {
            String message = String.format("Hook script failed: %s exited with code %d", scriptFileName, exitCode);
            throw new IllegalStateException(message);
        }
    }

    private int waitForProcessExitCode(Process process, String scriptFileName) throws InterruptedException {
        try {
            long timeoutSeconds = initHooksConfig.timeoutSeconds();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                LOG.debugv("Hook script exceeded timeout of {0} seconds, terminating process: {1}", timeoutSeconds, scriptFileName);
                terminateProcess(process, scriptFileName);

                String message = String.format("Hook script timed out after %d seconds: %s", timeoutSeconds, scriptFileName);
                throw new IllegalStateException(message);
            }

            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                LOG.debugv("Hook script process still alive during cleanup, forcing termination: {0}", scriptFileName);
                process.destroyForcibly();
            }
        }
    }

    private void terminateProcess(Process process, String scriptFileName) throws InterruptedException {
        // Try a graceful shutdown first, then force termination if the process does not exit in time.
        process.destroy();
        if (process.isAlive()) {
            long shutdownGracePeriodSeconds = initHooksConfig.shutdownGracePeriodSeconds();
            boolean terminatedGracefully = process.waitFor(shutdownGracePeriodSeconds, TimeUnit.SECONDS);
            if (!terminatedGracefully) {
                LOG.debugv("Hook script process did not terminate gracefully, forcing termination: {0}", scriptFileName);
                process.destroyForcibly();
                process.waitFor(shutdownGracePeriodSeconds, TimeUnit.SECONDS);
            }
        }
    }

}
