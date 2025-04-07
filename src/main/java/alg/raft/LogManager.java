package alg.raft;

import alg.raft.enums.EntryType;
import alg.raft.enums.NodeType;
import alg.raft.message.Configuration;
import alg.raft.message.ConfigurationType;
import alg.raft.message.LogEntry;
import alg.raft.state.NodeState;
import alg.raft.utils.JsonMapper;
import alg.raft.utils.Magics;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LogManager {

    private final Members members;
    private final NodeState state;

    // disk 에 관리돼야 함
    private final List<LogEntry> entries = new ArrayList<>();
    private final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public LogManager(Members members,
                      NodeState state
    ) {
        this.members = members;
        this.state = state;
    }

    @PostConstruct
    public void init() {
        executor.execute(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 큐에서 task를 꺼내어 실행 (큐가 비면 대기)
                    Runnable task = taskQueue.take();
                    task.run();
                } catch (InterruptedException e) {
                    // 인터럽트 발생 시 스레드 종료
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // 작업 실행 중 예외가 발생해도 worker 스레드가 종료되지 않도록 처리
                    _logger.error(e.getMessage(), e);
                }
            }
        });
    }

    public <T> void enqueue(EntryType type, T log) {
        final int size = entries.size();
        final LogEntry entry = new LogEntry(size, state.getCurrentTerm(), type, JsonMapper.writeValueAsString(log));
        entries.add(entry);

        // membership 중 quorum 이상의 동의를 받아야 함
        int quorum = 1 + members.getActiveChannelCount() / 2;
        AtomicInteger acknowledged = new AtomicInteger(1); // 자기 자신은 동의한채로 시작
        AtomicBoolean callbackTriggered = new AtomicBoolean(false);

        members.getActiveChannels().parallelStream()
            .forEach(channel -> {
                RaftServiceGrpc.RaftServiceStub stub = RaftServiceGrpc.newStub(channel.channel())
                    .withDeadlineAfter(
                        Magics.MAX_APPEND_ENTRIES_RPC_ASYNC_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS
                    );
                final int nextIndex = Math.max(0, (int) state.getNextIndex(channel.id()));
                final int lastIndex = Math.min(nextIndex + 100, entries.size());

                List<Entry> logs = entries.subList(nextIndex, lastIndex)
                    .stream()
                    .map(le -> Entry.newBuilder()
                        .setSequence(le.sequence())
                        .setTerm(le.term())
                        .setTypeValue(le.type().ordinal())
                        .setLog(le.log())
                        .build()
                    )
                    .toList();
                final LogEntry prev = getPrevEntry(nextIndex);

                AppendEntriesReq req = AppendEntriesReq.newBuilder()
                    .setTerm(state.getCurrentTerm())
                    .setLeaderId(state.getAppId())
                    .setPrevLogIndex(prev != null ? prev.sequence() : -1)
                    .setPrevLogTerm(prev != null ? prev.term() : state.getCurrentTerm())
                    .addAllEntries(logs)
                    .setLeaderCommit(state.getCommitIndex())
                    .build();

                // callback
                StreamObserver<AppendEntriesResp> observer = new StreamObserver<>() {
                    @Override
                    public void onNext(AppendEntriesResp resp) {
                        if (resp.getSuccess()) {
                            Entry lastLogEntry = logs.get(logs.size() - 1);
                            state.setMatchIndex(channel.id(), lastLogEntry.getSequence());
                            state.setNextIndex(channel.id(), lastLogEntry.getSequence()+1);
                            // quorum 이상의 동의를 얻은 경우 commit 처리
                            if (!callbackTriggered.get() && acknowledged.incrementAndGet() >= quorum) {
                                callbackTriggered.set(true);
                                _logger.info("Quorum of nodes acknowledged the log at {}", entry.sequence());
                                state.setCommitIndex(Math.max(state.getCommitIndex(), entry.sequence()));
                                // local state machine 에 반영
                                logs.stream()
                                    .filter(e -> state.getLastApplied() < e.getSequence())
                                    .forEach(e -> registerTask(e));
                            }
                        } else {
                            // entry 정보가 동일할 때까지 반복
                            state.setNextIndex(channel.id(), state.getNextIndex(channel.id())-1);
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        _logger.error("An error occurred on AppendEntries RPC({} -> {}).",
                            state.getAppId(),
                            channel.id(),
                            throwable
                        );
                    }

                    @Override
                    public void onCompleted() {
                        // do nothing
                    }
                };
                // rpc 전송
                stub.appendEntries(req, observer);
            });
    }

    public void registerTask(EntryType type, int sequence, String log) {
        boolean offered = taskQueue.offer(() -> {
            // 이미 반영한 로그는 처리하지 않음
            if (sequence < state.getLastApplied()) {
                _logger.info("Log at {} is already processed.", sequence);
                return;
            }

            _logger.info("Log at {} is on processing.", sequence);
            switch (type) {
                case CONFIGURATION -> {
                    Configuration configuration = JsonMapper.readValue(log, Configuration.class);
                    if (configuration.type() == ConfigurationType.JOINT) {
                        members.jointMembership(configuration.newConfiguration());
                        if (NodeType.LEADER == state.getType()) {
                            _logger.info("Leader would confirm the configuration log at {}", sequence);
                            // enqueue new log
                            Configuration finalConfiguration = new Configuration(
                                ConfigurationType.FINAL,
                                null,
                                configuration.newConfiguration()
                            );
                            enqueue(EntryType.CONFIGURATION, finalConfiguration);
                        }
                    } else if (configuration.type() == ConfigurationType.FINAL) {
                        members.updateMembership(configuration.newConfiguration());
                    } else {
                        _logger.error("Log at {} is not a valid configuration.", sequence);
                    }
                }
                case MESSAGE -> {
                    _logger.info("Message {} at sequence {} is committed.", log, sequence);
                }
                default -> {
                    //do nothing
                }
            }
            state.setLastApplied(sequence);
        });

        if (!offered) {
            _logger.warn("Failed to emit log at {}", sequence);
        }
    }

    public void registerTask(LogEntry entry) {
        registerTask(entry.type(), entry.sequence(), entry.log());
    }

    public void registerTask(Entry entry) {
        registerTask(EntryType.valueOf(entry.getType().name()), entry.getSequence(), entry.getLog());
    }

    public void replicate(long leaderCommit) {
        // 5. If leaderCommit > commitIndex, set commitIndex =
        // min(leaderCommit, index of last new entry)
        if (leaderCommit > state.getCommitIndex()) {
            state.setCommitIndex(Math.min(leaderCommit, state.getCommitIndex()));
        }
    }

    public void replicate(Entry entry) {
        // replicate log entries
        entries.add(new LogEntry(
            entry.getSequence(),
            entry.getTerm(),
            EntryType.valueOf(entry.getType().name()),
            entry.getLog()
        ));
    }

    public Iterable<LogEntry> getCommitedEntries() {
        return entries;
    }

    public LogEntry getEntry(int index) {
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        return entries.get(index);
    }

    public List<LogEntry> getEntries(int fromIndex, int toIndex) {
        if (fromIndex > toIndex
            || fromIndex >= entries.size()
        ) {
            return Collections.emptyList();
        }
        return entries.subList(Math.max(0, fromIndex), Math.min(toIndex + 1, entries.size()));
    }

    public LogEntry getLastEntry() {
        return entries.isEmpty() ?
            null :
            entries.get(entries.size() - 1);
    }

    public void deleteEntriesFromIndex(int index) {
        if (entries.size() > index) {
            entries.subList(index, entries.size()).clear();
        }
    }

    public LogEntry getPrevEntry(int index) {
        if (index <= 0 || index > entries.size()) {
            return null;
        }

        return entries.get(index - 1);
    }
}
