package io.floci.core.infra;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Registry and coordinator for all Resettable components across emulator services.
 */
public class ResettableManager {
    private static final Logger LOGGER = Logger.getLogger(ResettableManager.class.getName());
    private static final ResettableManager INSTANCE = new ResettableManager();

    public static ResettableManager getInstance() {
        return INSTANCE;
    }

    private final List<Resettable> resettables = new CopyOnWriteArrayList<>();

    public void register(Resettable resettable) {
        if (resettable != null && !resettables.contains(resettable)) {
            resettables.add(resettable);
        }
    }

    public void unregister(Resettable resettable) {
        resettables.remove(resettable);
    }

    public List<Resettable> getRegisteredResettables() {
        return Collections.unmodifiableList(resettables);
    }

    public ResetResult resetAll() {
        long startTime = System.currentTimeMillis();
        List<String> details = new ArrayList<>();
        int count = 0;
        boolean allSuccess = true;

        for (Resettable resettable : resettables) {
            try {
                resettable.reset();
                count++;
                details.add("Reset component: " + resettable.getClass().getSimpleName());
            } catch (Exception e) {
                allSuccess = false;
                LOGGER.severe("Error resetting component " + resettable.getClass().getName() + ": " + e.getMessage());
                details.add("Failed component: " + resettable.getClass().getSimpleName() + " (" + e.getMessage() + ")");
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        return new ResetResult(allSuccess, count, elapsed, details);
    }
}
