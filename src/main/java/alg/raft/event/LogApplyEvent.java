package alg.raft.event;

import alg.raft.enums.EntryType;

public record LogApplyEvent(
    EntryType type,
    long sequence,
    String log
) {
}
