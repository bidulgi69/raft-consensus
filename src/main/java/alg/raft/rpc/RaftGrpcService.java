package alg.raft.rpc;

import alg.raft.*;
import alg.raft.enums.NodeType;
import alg.raft.event.EventDispatcher;
import alg.raft.event.LogApplyEvent;
import alg.raft.message.LogEntry;
import alg.raft.state.NodeState;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RaftGrpcService extends RaftServiceGrpc.RaftServiceImplBase {

    private final NodeState state;
    private final LogManager logManager;
    private final LeaseManager leaseManager;
    private final ElectionManager electionManager;
    private final EventDispatcher eventDispatcher;
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public RaftGrpcService(NodeState state,
                           LogManager logManager,
                           LeaseManager leaseManager,
                           ElectionManager electionManager,
                           EventDispatcher eventDispatcher
    ) {
        this.state = state;
        this.logManager = logManager;
        this.electionManager = electionManager;
        this.eventDispatcher = eventDispatcher;
        this.leaseManager = leaseManager;
    }

    @Override
    public void appendEntries(AppendEntriesReq request, StreamObserver<AppendEntriesResp> responseObserver) {
        AppendEntriesResp.Builder defaultRespBuilder = AppendEntriesResp.newBuilder()
            .setTerm(state.getCurrentTerm())
            .setSuccess(false);
        List<Entry> entries = request.getEntriesList();

        // 1. Reply false if term < currentTerm (§5.1)
        // Notify term has become outdated.
        if (request.getTerm() < state.getCurrentTerm()) {
            responseObserver.onNext(defaultRespBuilder.build());
            responseObserver.onCompleted();
            return;
        }

        // 2. Reply false if log doesn’t contain an entry at prevLogIndex
        // whose term matches prevLogTerm (§5.3)
        if (request.getPrevLogIndex() >= 0) {
            LogEntry prev = logManager.getEntry(request.getPrevLogIndex());
            if (prev == null || prev.term() != request.getPrevLogTerm()) {
                responseObserver.onNext(defaultRespBuilder.build());
                responseObserver.onCompleted();
                return;
            }
        }

        _logger.info("Node({}) received log entries from the leader.", state.getAppId());
        NodeType currentState = state.getType();
        state.setType(NodeType.FOLLOWER);
        if (NodeType.LEADER == currentState) {
            // prevent split-brain
            leaseManager.stop();
            responseObserver.onNext(defaultRespBuilder.build());
            responseObserver.onCompleted();
            return;
        } else {
            state.setType(NodeType.FOLLOWER);
            if (state.getCurrentTerm() < request.getTerm()) {
                state.setCurrentTerm(request.getTerm());
                state.setVotedFor(null);
            }
            // 마지막으로 수신한 heartbeat 갱신
            electionManager.onHeartbeat();
        }

        // 3. If an existing entry conflicts with a new one (same index
        // but different terms), delete the existing entry and all that
        // follow it (§5.3)
        for (Entry entry : entries) {
            LogEntry existingEntry = logManager.getEntry(entry.getSequence());
            if (existingEntry != null && existingEntry.term() != request.getTerm()) {
                logManager.truncateFrom(entry.getSequence());
                existingEntry = null;
            }
            // 4. Append any new entries not already in the log
            if (existingEntry == null) {
                logManager.replicate(entry);
            }
        }

        // local state machine 에 반영
        List<LogEntry> acknowledged = logManager.getEntries((int) state.getLastApplied() + 1, (int) request.getLeaderCommit());
        acknowledged.forEach(le -> {
            state.setCommitIndex(le.sequence());
            eventDispatcher.dispatchLogApplyEvent(new LogApplyEvent(
                le.type(),
                le.sequence(),
                le.log()
            ));
        });

        responseObserver.onNext(defaultRespBuilder
            .setSuccess(true)
            .build()
        );
        responseObserver.onCompleted();
    }

    @Override
    public void requestVote(RequestVoteReq request, StreamObserver<RequestVoteResp> responseObserver) {
        _logger.info("Candidate({}) node request vote to this node({}) in term({}).", request.getCandidateId(), state.getAppId(), request.getTerm());
        long term = request.getTerm();
        long candidateId = request.getCandidateId();
        // attributes for determining the value of `voteGranted`
        long candidateLastLogIndex = request.getLastLogIndex();
        long candidateLastLogTerm = request.getLastLogTerm();

        // 1. Reply false if term < currentTerm (§5.1)
        if (state.getCurrentTerm() > term ||                                // 자신의 임기가 더 높은 경우
            (state.getCurrentTerm() == term && state.getVotedFor() != null) // 임기는 동일하지만, 이미 투표한 경우
        ) {
            responseObserver.onNext(RequestVoteResp.newBuilder()
                .setTerm(state.getCurrentTerm())
                .setVoteGranted(false)
                .build()
            );
            responseObserver.onCompleted();
            return;
        }

        // 2. If votedFor is null or candidateId, and candidate’s log is at
        // least as up-to-date as receiver’s log, grant vote (§5.2, §5.4)
        LogEntry receiverLogEntry = logManager.getEntry(candidateLastLogIndex);
        if (receiverLogEntry != null && receiverLogEntry.term() > candidateLastLogTerm) {
            responseObserver.onNext(RequestVoteResp.newBuilder()
                .setTerm(state.getCurrentTerm())
                .setVoteGranted(false)
                .build()
            );
            responseObserver.onCompleted();
            return;
        }

        state.setCurrentTerm(Math.max(state.getCurrentTerm(), term));
        state.setVotedFor(candidateId);
        responseObserver.onNext(RequestVoteResp.newBuilder()
            .setTerm(state.getCurrentTerm())
            .setVoteGranted(true)
            .build()
        );
        responseObserver.onCompleted();
    }

    @Override
    public void installSnapshot(InstallSnapshotReq request, StreamObserver<InstallSnapshotResp> responseObserver) {
        InstallSnapshotResp resp = InstallSnapshotResp.newBuilder()
            .setTerm(state.getCurrentTerm())
            .build();

        //1. Reply immediately if term < currentTerm
        if (request.getTerm() < state.getCurrentTerm()) {
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
            return;
        }

        logManager.installSnapshot(request.getLastIncludedIndex(),
            request.getLastIncludedTerm(),
            request.getOffset(),
            request.getData(),
            request.getDone()
        );
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }
}
