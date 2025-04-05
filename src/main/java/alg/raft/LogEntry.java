package alg.raft;

import alg.raft.enums.EntryType;

// 결국은 이 클래스가 grpc 통해서 전달될거임
public record LogEntry(
    int sequence,
    long term,
    EntryType type,
    String log
) {
}