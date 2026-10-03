package dev.jobs.worker;

import dev.jobs.job.JobStore;
import dev.jobs.job.WorkerRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The workers running inside this instance. Normally one. The demo can start more, and
 * crash them, so the dashboard has something to show without a second machine.
 */
@Component
public class Workers implements SmartLifecycle {

    private final JobStore store;
    private final WorkerRegistry registry;
    private final List<JobHandler> handlers;
    private final WorkerProperties properties;
    private final List<WorkerPool> pools = new CopyOnWriteArrayList<>();
    private volatile boolean running;

    Workers(JobStore store, WorkerRegistry registry, List<JobHandler> handlers, WorkerProperties properties) {
        this.store = store;
        this.registry = registry;
        this.handlers = handlers;
        this.properties = properties;
    }

    public List<String> knownTypes() {
        return handlers.stream().map(JobHandler::type).sorted().toList();
    }

    public WorkerPool startAnother() {
        // Host name plus a random suffix: unique across instances and across restarts, so a
        // restarted process can never be mistaken for the one that held the old leases.
        WorkerPool pool = new WorkerPool(
                store, registry, handlers, properties.settingsFor(host() + "-" + UUID.randomUUID().toString().substring(0, 6)));
        pools.add(pool);
        pool.start();
        return pool;
    }

    public Optional<WorkerPool> find(String id) {
        return pools.stream().filter(pool -> pool.id().equals(id)).findFirst();
    }

    public boolean crash(String id) {
        Optional<WorkerPool> pool = find(id);
        pool.ifPresent(found -> {
            found.kill();
            pools.remove(found);
        });
        return pool.isPresent();
    }

    @Override
    public void start() {
        running = true;
        if (properties.worker().enabled()) {
            startAnother();
        }
    }

    @Override
    public void stop() {
        running = false;
        // In parallel: each may wait out the grace period.
        pools.parallelStream().forEach(WorkerPool::stop);
        pools.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "worker";
        }
    }
}
