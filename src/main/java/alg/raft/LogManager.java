package alg.raft;

import alg.raft.configuration.RaftProperties;
import alg.raft.db.LocalDatabase;
import alg.raft.enums.EntryType;
import alg.raft.event.EventDispatcher;
import alg.raft.event.ReplicateEvent;
import alg.raft.message.LogEntry;
import alg.raft.state.NodeState;
import alg.raft.utils.JsonMapper;
import alg.raft.utils.Magics;
import com.google.protobuf.ByteString;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class LogManager {

    private final NodeState state;
    private final RaftProperties properties;
    private final EventDispatcher eventDispatcher;

    private final List<LogEntry> logEntries = new ArrayList<>();
    private final Executor logEnqueueExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService snapshotExecutor = Executors.newSingleThreadScheduledExecutor();

    // local state machine
    private final LocalDatabase localDatabase;
    // snapshot
    private final SnapshotManager snapshotManager;
    private Long lastIncludedIndex = null;
    private Long lastIncludedTerm = null;

    private final Logger _logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public LogManager(NodeState state,
                      RaftProperties properties,
                      EventDispatcher eventDispatcher,
                      LocalDatabase localDatabase,
                      SnapshotManager snapshotManager
    ) {
        this.state = state;
        this.properties = properties;
        this.eventDispatcher = eventDispatcher;
        this.localDatabase = localDatabase;
        this.snapshotManager = snapshotManager;
    }

    @PostConstruct
    public void init() {
        snapshotExecutor.scheduleAtFixedRate(() -> {
            if (logEntries.size() >= properties.getCompactionThreshold()) {
                _logger.info("Compaction threshold reached!");
                long lastAppliedIndex = state.getLastApplied();
                LogEntry lastLogEntry = getEntry(lastAppliedIndex);
                boolean created = snapshotManager.createSnapshot(lastLogEntry, localDatabase);
                if (created) {
                    _logger.info("Successfully created a snapshot with lastIncludedIndex: {}", lastLogEntry.sequence());
                    lastIncludedIndex = lastLogEntry.sequence();
                    lastIncludedTerm = lastLogEntry.term();
                    truncatedTo(lastAppliedIndex);
                }
            }
        },
            properties.getCompactionInitDelay(),
            properties.getCompactionInterval(),
            TimeUnit.MILLISECONDS
        );
    }

    // use a single-threaded executor to avoid contention for sequence generation
    public <T> void enqueue(EntryType type, T log) {
        logEnqueueExecutor.execute(() -> enqueue0(type, log));
    }

    private <T> void enqueue0(EntryType type, T log) {
        final LogEntry entry = new LogEntry(
            lastLogIndex()+1,
            state.getCurrentTerm(),
            type,
            JsonMapper.writeValueAsString(log)
        );
        _logger.info("A new log is appended to the queue. {}", log);
        logEntries.add(entry);
        eventDispatcher.dispatchReplicateEvent(new ReplicateEvent(
            Magics.MAX_APPEND_ENTRIES_RPC_ASYNC_TIMEOUT_MILLIS
        ));
    }

    public void replicate(Entry entry) {
        // replicate log entries
        logEntries.add(new LogEntry(
            entry.getSequence(),
            entry.getTerm(),
            EntryType.valueOf(entry.getType().name()),
            entry.getLog()
        ));
    }

    public Iterable<LogEntry> getCommitedEntries() {
        return logEntries;
    }

    public LogEntry getEntry(long sequence) {
        for (LogEntry entry : logEntries) {
            if (entry.sequence() == sequence) {
                return entry;
            }
        }
        return null;
    }

    public List<LogEntry> getEntries(long fromSequence, long toSequence) {
        if (fromSequence > toSequence) {
            return Collections.emptyList();
        }

        int fromIndex = 0;
        int toIndex = 0;
        for (int i = 0; i < logEntries.size(); i++) {
            if (logEntries.get(i).sequence() == fromSequence) {
                fromIndex = i;
            }
            if (logEntries.get(i).sequence() == toSequence) {
                toIndex = i+1;
                break;
            }
        }

        if (fromIndex > toIndex) {
            return Collections.emptyList();
        }
        return logEntries.subList(fromIndex, toIndex);
    }

    public LogEntry getLastEntry() {
        return logEntries.isEmpty() ?
            null :
            logEntries.get(logEntries.size() - 1);
    }

    public void truncateFrom(long sequence) {
        logEntries.removeIf(e -> e.sequence() >= sequence);
    }

    public void truncatedTo(long sequence) {
        logEntries.removeIf(e -> e.sequence() <= sequence);
    }

    private long lastLogIndex() {
        if (logEntries.isEmpty()) {
            return lastIncludedIndex == null ? 0 : lastIncludedIndex;
        } else {
            return logEntries.get(logEntries.size() - 1).sequence();
        }
    }

    public Long getLastIncludedIndex() {
        return lastIncludedIndex;
    }

    public Long getLastIncludedTerm() {
        return lastIncludedTerm;
    }

    public synchronized void installSnapshot(long lastIncludedIndex, long lastIncludedTerm,
                                             long offset, ByteString data, boolean done
    ) {
        Snapshot snapshot = snapshotManager.installSnapshot(lastIncludedIndex, lastIncludedTerm, offset, data, done);
        if (snapshot != null) {
            try {
                localDatabase.restore(snapshot.data());
                this.lastIncludedIndex = snapshot.lastIncludedIndex();
                this.lastIncludedTerm = snapshot.lastIncludedTerm();
            } catch (IOException e) {
                _logger.info("Failed to restore snapshot.", e);
            }
        }
    }
}
