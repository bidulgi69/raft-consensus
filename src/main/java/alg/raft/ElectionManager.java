package alg.raft;

import alg.raft.configuration.RaftProperties;
import alg.raft.enums.NodeType;
import alg.raft.event.EventDispatcher;
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

import java.util.Collection;
import java.util.concurrent.*;

@Component
public class ElectionManager {

    private final NodeState state;
    private final Members members;
    private final LogManager logManager;
    private final LeaseManager leaseManager;
    private final RaftProperties properties;
    private final EventDispatcher eventDispatcher;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile long lastHeartbeat = System.currentTimeMillis();
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public ElectionManager(NodeState state,
                           Members members,
                           LogManager logManager,
                           LeaseManager leaseManager,
                           RaftProperties properties,
                           EventDispatcher eventDispatcher
    ) {
        this.state = state;
        this.members = members;
        this.logManager = logManager;
        this.leaseManager = leaseManager;
        this.properties = properties;
        this.eventDispatcher = eventDispatcher;
    }

    public void onHeartbeat() {
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public void startElectionScheduler() {
        long interval = properties.getElectionMinTimeout() / 5;
        scheduler.scheduleAtFixedRate(() -> {
            if (NodeType.LEADER == state.getType()) {
                // 리더인 경우 재선거를 일으키지 않도록 방어
                return;
            }
            long elapsed = System.currentTimeMillis() - lastHeartbeat;
            long timeout = getRandomTimeout();
            if (elapsed >= timeout) {
                raiseElection();
                lastHeartbeat = System.currentTimeMillis();
            }
        }, 0,    // initial delay
            interval,       // interval (fixed rate)
            TimeUnit.MILLISECONDS
        );
    }

    public synchronized void raiseElection() {
        long term = state.incrementAndGetTerm();
        _logger.info("Node({}) starts an election for {}th term", state.getAppId(), term);
        state.setVotedFor((long) state.getAppId());  // vote for me
        state.setType(NodeType.CANDIDATE);

        // members 에 투표 요청 전송
        Collection<Channel> channels = members.getActiveChannels();

        int granted = 1;
        int quorum = 1 + members.getActiveChannelCount() / 2;
        int leftCandidates = channels.size();
        for (Channel channel : channels) {
            RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(channel.channel())
                .withDeadlineAfter(Magics.DEFAULT_VOTE_RPC_SYNC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            LogEntry lastLogEntry = logManager.getLastEntry();
            RequestVoteReq req = RequestVoteReq.newBuilder()
                .setTerm(term)
                .setCandidateId(state.getAppId()) // 현재 node 의 ID 정보
                .setLastLogIndex(lastLogEntry != null ? lastLogEntry.sequence() : 0)
                .setLastLogTerm(lastLogEntry != null ? lastLogEntry.term() : term)
                .build();

            try {
                RequestVoteResp resp = stub.requestVote(req);
                _logger.info("Node({}) {} this node in {}th term.", channel.id(), resp.getVoteGranted() ? "grants" : "revokes", term);
                if (resp.getVoteGranted()) {
                    granted++;
                } else if (resp.getTerm() > term) {
                    break;
                }
            } catch (StatusRuntimeException e) {
                RpcErrorHandler.handleRpcError(new RpcErrorContext(
                    channel,
                    e,
                    state,
                    eventDispatcher
                ));
            }

            if (quorum - granted > --leftCandidates) {
                break;
            }
        }

        // quorum 이상의 node 에게 success 응답을 받은 경우
        // 자신을 leader 로 설정한다
        if (granted >= quorum) {
            _logger.info("Node {} has elected as leader for {}th term", state.getAppId(), term);
            state.setType(NodeType.LEADER);
            leaseManager.run();
            members.getActiveChannels().forEach(channel -> {
                long leaderCommitIndex = state.getCommitIndex();
                state.setNextIndex(channel.id(), leaderCommitIndex);
                state.setMatchIndex(channel.id(), 0L);
            });
        }
    }

    private long getRandomTimeout() {
        return ThreadLocalRandom.current().nextLong(
            properties.getElectionMinTimeout(),
            properties.getElectionMaxTimeout()
        );
    }

    @PreDestroy
    public void shutdown() {
        if (!scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }
}
