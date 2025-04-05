package alg.raft.state;

import alg.raft.enums.NodeType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NodeState {

    private int appId;
    private NodeType type = NodeType.FOLLOWER;
    private long currentTerm;
    private volatile Long votedFor = null;

    // 커밋된 로그의 마지막 인덱스
    private volatile long commitIndex;
    // local state machine 에 적용된 마지막 로그 인덱스
    private volatile long lastApplied;

    // state on leaders
    private final Map<String, Long> nextIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> matchIndex = new ConcurrentHashMap<>();

    public void setAppId(int appId) {
        this.appId = appId;
    }

    public int getAppId() {
        return appId;
    }

    public void setType(NodeType type) {
        this.type = type;
    }

    public NodeType getType() {
        return type;
    }

    public synchronized long incrementAndGetTerm() {
        return ++currentTerm;
    }

    public synchronized void setCurrentTerm(long currentTerm) {
        this.currentTerm = currentTerm;
    }

    public long getCurrentTerm() {
        return currentTerm;
    }

    public synchronized Long getVotedFor() {
        return votedFor;
    }

    public synchronized void setVotedFor(Long votedFor) {
        this.votedFor = votedFor;
    }

    public synchronized void setCommitIndex(long commitIndex) {
        this.commitIndex = commitIndex;
    }

    public synchronized void setLastApplied(long lastApplied) {
        this.lastApplied = lastApplied;
    }

    public synchronized long getLastApplied() {
        return lastApplied;
    }

    public synchronized void setNextIndex(String id, long index) {
        nextIndex.put(id, index);
    }

    public long getNextIndex(String id) {
        return nextIndex.getOrDefault(id, 0L);
    }

    public synchronized void setMatchIndex(String id, long index) {
        matchIndex.put(id, index);
    }

    public long getMatchIndex(String id) {
        return matchIndex.getOrDefault(id, 0L);
    }

    public long getCommitIndex() {
        return commitIndex;
    }

    @Override
    public String toString() {
        return """
            [Current State]
            | appId: %s
            | type: %s
            | currentTerm: %d
            | votedFor: %d
            | commitIndex: %d
            | lastApplied: %d
            ---
            [Leader State]
            | nextIndex: %s
            | matchIndex: %s
            """
            .formatted(
                appId,
                type,
                currentTerm,
                votedFor,
                commitIndex,
                lastApplied,
                nextIndex.entrySet().toString(),
                matchIndex.entrySet().toString()
            );
    }
}