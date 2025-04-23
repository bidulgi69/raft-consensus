package alg.raft.db;

import java.io.Serializable;

public record Entity(
    long id,
    String value
) implements Serializable {
}
