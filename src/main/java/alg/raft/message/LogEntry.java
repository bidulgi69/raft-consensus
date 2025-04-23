package alg.raft.message;

import alg.raft.enums.EntryType;

public record LogEntry(
    long sequence,
    long term,
    EntryType type,
    String log
) {
}