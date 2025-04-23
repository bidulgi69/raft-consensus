package alg.raft;

public record Snapshot(
    long lastIncludedIndex,
    long lastIncludedTerm,
    byte[] data
) {
}
