package alg.raft.event;

public record ReplicateEvent(
    int responseTimeout
) {
}
