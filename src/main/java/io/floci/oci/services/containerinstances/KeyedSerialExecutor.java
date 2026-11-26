package io.floci.oci.services.containerinstances;

import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs the tasks of one key in submission order, one at a time, while tasks of different keys run
 * independently on {@code backing}. Container Instances keys it by instance OCID, so one
 * instance's lifecycle steps and restart-policy checks never overlap, and a slow image pull for
 * one instance never delays another.
 */
final class KeyedSerialExecutor {

    private static final Logger LOG = Logger.getLogger(KeyedSerialExecutor.class);

    private final Executor backing;
    private final Map<String, Deque<Runnable>> queues = new HashMap<>();

    KeyedSerialExecutor(Executor backing) {
        this.backing = backing;
    }

    void execute(String key, Runnable task) {
        boolean idle;
        synchronized (queues) {
            Deque<Runnable> queue = queues.get(key);
            idle = queue == null;
            if (idle) {
                queue = new ArrayDeque<>();
                queues.put(key, queue);
            }
            queue.add(task);
        }
        if (!idle) {
            return;
        }
        try {
            backing.execute(() -> drain(key));
        } catch (RejectedExecutionException e) {
            synchronized (queues) {
                queues.remove(key);
            }
            LOG.warnv("Dropped the background work of {0}: {1}", key, e.getMessage());
        }
    }

    private void drain(String key) {
        while (true) {
            Runnable next;
            synchronized (queues) {
                Deque<Runnable> queue = queues.get(key);
                next = queue.poll();
                if (next == null) {
                    queues.remove(key);
                    return;
                }
            }
            try {
                next.run();
            } catch (RuntimeException e) {
                LOG.warnv(e, "Background work of {0} failed", key);
            }
        }
    }
}
