package alg.raft;

import alg.raft.enums.NodeType;
import alg.raft.message.LogEntry;
import alg.raft.state.NodeState;
import alg.raft.utils.Magics;
import alg.raft.utils.RpcErrorContext;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class LeaseManager {

    private final NodeState state;
    private final Members members;
    private final LogManager logManager;
    private final RaftProperties properties;

    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> heartbeat;
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public LeaseManager(NodeState state,
                        Members members,
                        LogManager logManager,
                        RaftProperties properties
    ) {
        this.state = state;
        this.members = members;
        this.logManager = logManager;
        this.properties = properties;
    }

    // tasks in leader node
    public void run() {
        _logger.info("Sending leases to followers...");
        heartbeat = scheduledExecutorService.scheduleAtFixedRate(() -> members.getActiveChannels()
            .parallelStream()
            .forEach(channel -> {
                final int nextIndex = (int) state.getNextIndex(channel.id());
                final int lastIndex = nextIndex + 100;
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
                LogEntry prev = logManager.getPrevEntry(nextIndex);

                RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(channel.channel())
                    .withDeadlineAfter(
                        logs.isEmpty() ?
                            Magics.DEFAULT_HEARTBEAT_RPC_SYNC_TIMEOUT_MILLIS
                            : Magics.MAX_APPEND_ENTRIES_RPC_SYNC_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS
                    );
                AppendEntriesReq req = AppendEntriesReq.newBuilder()
                    .setTerm(state.getCurrentTerm())
                    .setLeaderId(state.getAppId())   //  heartbeat 를 전송하는 것은 leader node
                    .setPrevLogIndex(prev != null ? prev.sequence() : -1)
                    .setPrevLogTerm(prev != null ? prev.term() : state.getCurrentTerm())
                    .addAllEntries(logs) //  빈 배열인 경우 heartbeat
                    .setLeaderCommit(state.getCommitIndex())
                    .build();
                try {
                    // send heartbeat
                    AppendEntriesResp heartbeatResp = stub.appendEntries(req);
                    if (!heartbeatResp.getSuccess() && state.getCurrentTerm() < heartbeatResp.getTerm()) {
                        // outdated 된 경우 자신을 follower 로 변경한다.
                        state.setType(NodeType.FOLLOWER);
                        stop();
                    } else if (!logs.isEmpty()) {
                        if (heartbeatResp.getSuccess()) {
                            Entry lastLogEntry = logs.get(logs.size() - 1);
                            state.setMatchIndex(channel.id(), lastLogEntry.getSequence());
                            state.setNextIndex(channel.id(), lastLogEntry.getSequence()+1);
                        } else {
                            // entry 정보가 동일할 때까지 반복
                            state.setNextIndex(channel.id(), state.getNextIndex(channel.id())-1);
                        }
                    }
                } catch (StatusRuntimeException e) {
                    _logger.error("RaftServiceGrpc.appendEntries(heartbeat) failed by status {}", e.getStatus().getCode().name());
                    RpcErrorHandler.handleRpcError(new RpcErrorContext(
                        channel,
                        e,
                        state,
                        members,
                        logManager
                    ));
                }
            }), 0, properties.getLeaseInterval(), TimeUnit.MILLISECONDS);
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
