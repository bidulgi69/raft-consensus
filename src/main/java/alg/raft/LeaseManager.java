package alg.raft;

import alg.raft.configuration.RaftProperties;
import alg.raft.event.EventDispatcher;
import alg.raft.event.ReplicateEvent;
import alg.raft.utils.Magics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class LeaseManager {

    private final EventDispatcher eventDispatcher;
    private final RaftProperties properties;

    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> heartbeat;
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public LeaseManager(EventDispatcher eventDispatcher,
                        RaftProperties properties
    ) {
        this.eventDispatcher = eventDispatcher;
        this.properties = properties;
    }

    // tasks in leader node
    public void run() {
        _logger.info("Sending leases to followers...");
        heartbeat = scheduledExecutorService.scheduleAtFixedRate(() ->
            eventDispatcher.dispatchReplicateEvent(new ReplicateEvent(
                Magics.MAX_APPEND_ENTRIES_RPC_SYNC_TIMEOUT_MILLIS
            )), 0, properties.getLeaseInterval(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
    }

    @PreDestroy
    public void destroy() {
        if (heartbeat != null) {
            heartbeat.cancel(true);
        }
        scheduledExecutorService.shutdown();
    }
}
