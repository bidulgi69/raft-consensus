package alg.raft;

import alg.raft.enums.EntryType;
import alg.raft.enums.NodeType;
import alg.raft.event.EventDispatcher;
import alg.raft.event.LogApplyEvent;
import alg.raft.event.ReplicateEvent;
import alg.raft.message.LogEntry;
import alg.raft.rpc.AppendEntriesRpcMetadata;
import alg.raft.rpc.InstallSnapshotRpcMetadata;
import alg.raft.state.NodeState;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Component
public class Replicator {

    private final NodeState state;
    private final Members members;
    private final LogManager logManager;
    private final LeaseManager leaseManager;
    private final SnapshotManager snapshotManager;
    private final EventDispatcher eventDispatcher;

    private final ExecutorService replicateExecutor = Executors.newSingleThreadExecutor();
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public Replicator(NodeState state,
                      Members members,
                      LogManager logManager,
                      LeaseManager leaseManager,
                      SnapshotManager snapshotManager,
                      EventDispatcher eventDispatcher
    ) {
        this.state = state;
        this.members = members;
        this.logManager = logManager;
        this.leaseManager = leaseManager;
        this.snapshotManager = snapshotManager;
        this.eventDispatcher = eventDispatcher;
        this.eventDispatcher.registerReplicateEventConsumer(schedule());
    }

    public Consumer<ReplicateEvent> schedule() {
        return event -> replicateExecutor.execute(() -> doReplicate(event));
    }

    private void doReplicate(ReplicateEvent event) {
        _logger.info("Received replicate event at {}", Instant.now());
        Collection<Channel> activeChannels = members.getActiveChannels();
        // membership 중 quorum 이상의 동의를 받아야 함
        final int quorum = 1 + activeChannels.size() / 2;
        AtomicInteger acknowledged = new AtomicInteger(1); // 자기 자신은 동의한채로 시작
        AtomicBoolean callbackTriggered = new AtomicBoolean(false);

        activeChannels.parallelStream().forEach(channel -> {
            RaftServiceGrpc.RaftServiceStub stub = RaftServiceGrpc.newStub(channel.channel())
                .withDeadlineAfter(event.responseTimeout(), TimeUnit.MILLISECONDS);
            final long nextIndex = Math.max(0L, state.getNextIndex(channel.id()));
            if (logManager.getLastIncludedIndex() != null && nextIndex <= logManager.getLastIncludedIndex()) {
                // snapshot
                _logger.info("Trying to call InstallSnapshot RPC.");
                Snapshot snapshot = null;
                try {
                    snapshot = snapshotManager.loadSnapshot();
                } catch (IOException e) {
                    _logger.error("Failed to load snapshot.", e);
                }

                if (snapshot != null) {
                    byte[] data = snapshot.data();
                    int totalLength = data.length;
                    final int chunkSize = 16 * 1024; // 16kb
                    int offset = 0;
                    long lastIncludedIndex = logManager.getLastIncludedIndex();
                    long lastIncludedTerm = logManager.getLastIncludedTerm();

                    while (offset < totalLength) {
                        int len = Math.min(chunkSize, totalLength - offset);
                        ByteString chunk = ByteString.copyFrom(data, offset, len);
                        InstallSnapshotReq req = InstallSnapshotReq.newBuilder()
                            .setTerm(state.getCurrentTerm())
                            .setLeaderId(state.getAppId())
                            .setLastIncludedIndex(lastIncludedIndex)
                            .setLastIncludedTerm(lastIncludedTerm)
                            .setOffset(offset)
                            .setData(chunk)
                            .setDone(offset+len == totalLength)
                            .build();

                        offset += len;
                        stub.installSnapshot(req, installSnapshotObserver(new InstallSnapshotRpcMetadata(
                            channel,
                            acknowledged,
                            req
                        )));
                    }
                }
            } else {
                LogEntry lastLogEntry = logManager.getLastEntry();
                final long lastIndex = lastLogEntry != null ? lastLogEntry.sequence() : 0;
                List<Entry> logs = logManager.getEntries(nextIndex, lastIndex)
                    .stream()
                    .map(le -> Entry.newBuilder()
                        .setSequence(le.sequence())
                        .setTerm(le.term())
                        .setTypeValue(le.type().ordinal())
                        .setLog(le.log())
                        .build()
                    )
                    .toList();
                final LogEntry prev = logManager.getEntry(nextIndex - 1);

                AppendEntriesReq req = AppendEntriesReq.newBuilder()
                    .setTerm(state.getCurrentTerm())
                    .setLeaderId(state.getAppId())
                    .setPrevLogIndex(prev != null ? prev.sequence() : -1)
                    .setPrevLogTerm(prev != null ? prev.term() : state.getCurrentTerm())
                    .addAllEntries(logs)
                    .setLeaderCommit(state.getCommitIndex())
                    .build();

                // callback
                StreamObserver<AppendEntriesResp> observer = appendEntriesObserver(new AppendEntriesRpcMetadata(
                    channel,
                    acknowledged,
                    req,
                    quorum,
                    callbackTriggered
                ));
                // rpc 전송
                stub.appendEntries(req, observer);
            }
        });
    }

    private StreamObserver<InstallSnapshotResp> installSnapshotObserver(InstallSnapshotRpcMetadata metadata) {
        return new StreamObserver<>() {
            @Override
            public void onNext(InstallSnapshotResp resp) {
                if (state.getCurrentTerm() < resp.getTerm()) {
                    state.setType(NodeType.FOLLOWER);
                    leaseManager.stop();
                }

                // 마지막 chunk 에 대한 응답 처리
                if (metadata.request().getDone()) {
                    metadata.acknowledged().incrementAndGet();
                    state.setMatchIndex(metadata.channel().id(), metadata.request().getLastIncludedIndex());
                    state.setNextIndex(metadata.channel().id(), metadata.request().getLastIncludedIndex()+1);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                _logger.error("An error occurred on InstallSnapshot RPC({} -> {}).",
                    state.getAppId(),
                    metadata.channel().id(),
                    throwable
                );
            }

            @Override
            public void onCompleted() {
                // do nothing
            }
        };
    }

    private StreamObserver<AppendEntriesResp> appendEntriesObserver(AppendEntriesRpcMetadata metadata) {
        Channel channel = metadata.channel();
        List<Entry> logs = metadata.request().getEntriesList();

        return new StreamObserver<>() {
            @Override
            public void onNext(AppendEntriesResp resp) {
                if (resp.getSuccess() && !logs.isEmpty()) {
                    Entry lastLogEntry = logs.get(logs.size() - 1);
                    state.setMatchIndex(channel.id(), lastLogEntry.getSequence());
                    state.setNextIndex(channel.id(), lastLogEntry.getSequence()+1);
                    // quorum 이상의 동의를 얻은 경우 commit 처리
                    if (!metadata.callbackTriggered().get() &&
                        metadata.acknowledged().incrementAndGet() >= metadata.quorum()
                    ) {
                        metadata.callbackTriggered().set(true);
                        _logger.info("Quorum of nodes acknowledged the log at {}", lastLogEntry.getSequence());
                        state.setCommitIndex(Math.max(state.getCommitIndex(), lastLogEntry.getSequence()));
                        // local state machine 에 반영
                        logs.stream()
                            .filter(e -> state.getLastApplied() < e.getSequence())
                            .forEach(e -> eventDispatcher.dispatchLogApplyEvent(new LogApplyEvent(
                                EntryType.valueOf(e.getType().name()),
                                e.getSequence(),
                                e.getLog()
                            )));
                    }
                } else if (!resp.getSuccess()) {
                    if (state.getCurrentTerm() < resp.getTerm()) {
                        state.setType(NodeType.FOLLOWER);
                        leaseManager.stop();
                    }
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
    }
}
