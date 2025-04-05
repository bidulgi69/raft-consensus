package alg.raft;

import alg.raft.enums.EntryType;
import alg.raft.enums.NodeType;
import alg.raft.message.Configuration;
import alg.raft.message.ConfigurationType;
import alg.raft.state.NodeState;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

@Component
public class ElectionManager {

    private final NodeState state;
    private final Members members;
    private final LogManager logManager;
    private final LeaseManager leaseManager;
    private final RaftProperties properties;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> scheduledFuture;
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public ElectionManager(NodeState state,
                           Members members,
                           LogManager logManager,
                           LeaseManager leaseManager,
                           RaftProperties properties
    ) {
        this.state = state;
        this.members = members;
        this.logManager = logManager;
        this.leaseManager = leaseManager;
        this.properties = properties;
    }

    public void reschedule() {
        if (scheduledFuture != null && !scheduledFuture.isDone()) {
            scheduledFuture.cancel(false);
        }

        scheduledFuture = scheduler.schedule(
            this::raiseElection,
            getRandomTimeout(),
            TimeUnit.MILLISECONDS
        );
    }

    public synchronized void raiseElection() {
        if (state.getVotedFor() != null) {
            return;
        }

        long term = state.incrementAndGetTerm();
        _logger.info("Node({}) starts an election for {}th term", state.getAppId(), term);
        state.setVotedFor((long) state.getAppId());  // vote for me
        state.setType(NodeType.CANDIDATE);

        // members 에 투표 요청 전송
        Collection<Channel> channels = members.getActiveChannels();

        int granted = 1;
        int quorum = 1 + ((channels.size()+1) / 2);
        int leftCandidates = channels.size();
        for (Channel channel : channels) {
            RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(channel.channel());
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
                if (Status.Code.UNAVAILABLE.value() == e.getStatus().getCode().value()) {
                    Set<String> oldConfiguration = members.getActiveChannelHosts();
                    Set<String> newConfiguration = new HashSet<>(oldConfiguration);
                    newConfiguration.remove(channel.host());
                    Configuration configuration = new Configuration(
                        ConfigurationType.JOINT,
                        oldConfiguration,
                        newConfiguration
                    );
                    logManager.enqueue(EntryType.CONFIGURATION, configuration);
                }
            }

            if (quorum - granted > --leftCandidates) {
                break;
            }
        }

        // quorum 이상의 node 에게 success 응답을 받은 경우
        // 자신을 leader 로 설정한다
        if (granted >= quorum) {
            _logger.info("Node {} has elected for {}th term", state.getAppId(), term);
            state.setType(NodeType.LEADER);
            state.setVotedFor(null);
            leaseManager.run();
            members.getActiveChannels().forEach(channel -> {
                long leaderCommitIndex = state.getCommitIndex();
                state.setNextIndex(channel.id(), leaderCommitIndex);
                state.setMatchIndex(channel.id(), 0L);
            });
        } else {
            reschedule();
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
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        scheduler.shutdown();
    }
}
