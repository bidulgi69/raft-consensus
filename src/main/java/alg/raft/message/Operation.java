package alg.raft.message;

import alg.raft.db.Entity;
import alg.raft.enums.OperationType;
import jakarta.annotation.Nullable;

public record Operation(
    OperationType type,
    long id,
    @Nullable Entity entity
) {
}
