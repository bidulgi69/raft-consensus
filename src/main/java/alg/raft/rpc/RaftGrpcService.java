package alg.raft.rpc;

import alg.raft.*;
import alg.raft.enums.NodeType;
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
    private final ElectionManager electionManager;
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public RaftGrpcService(NodeState state,
                           LogManager logManager,
                           ElectionManager electionManager
    ) {
        this.state = state;
        this.logManager = logManager;
        this.electionManager = electionManager;
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
        state.setType(NodeType.FOLLOWER);
        if (state.getCurrentTerm() < request.getTerm()) {
            state.setCurrentTerm(request.getTerm());
            state.setVotedFor(null);
        }

        // reply on heartbeat
        if (entries.isEmpty()) {
            _logger.info("Node({}) received a `Heartbeat` from the leader({}).", state.getAppId(), request.getLeaderId());
            // 하트비트 수신 시 election timer 재설정
            electionManager.reschedule();
        }

        // 3. If an existing entry conflicts with a new one (same index
        // but different terms), delete the existing entry and all that
        // follow it (§5.3)
        int replicateFromIndex = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            LogEntry existingEntry = logManager.getEntry(entry.getSequence());
            if (existingEntry != null && existingEntry.term() != entry.getTerm()) {
                logManager.deleteEntriesFromIndex(entry.getSequence());
                replicateFromIndex = i;
                // entry 는 정렬된 상태이므로, 한번이라도 수행되면 이후의 처리는 넘어가도 됨.
                break;
            } else if (existingEntry != null) {
                replicateFromIndex = i+1;
            }
        }

        // 4. Append any new entries not already in the log
        for (int i = replicateFromIndex; i < entries.size(); i++) {
            Entry log = entries.get(i);
            _logger.info("Received log details: [{}, {}, {}, {}}]", log.getSequence(), log.getTerm(), log.getType(), log.getLog());
            logManager.replicate(log);
        }

        // local state machine 에 반영
        List<LogEntry> acknowledged = logManager.getEntries((int) state.getLastApplied() + 1, (int) request.getLeaderCommit());
        acknowledged.forEach(le -> {
            state.setCommitIndex(le.sequence());
            logManager.registerTask(le);
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
        int candidateLastLogIndex = request.getLastLogIndex();
        long candidateLastLogTerm = request.getLastLogTerm();

        // 1. Reply false if term < currentTerm (§5.1)
        if (state.getCurrentTerm() > term || state.getVotedFor() != null) {
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
}
