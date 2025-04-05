package alg.raft;

import alg.raft.enums.EntryType;

public record ClientCommand(
    String message
) {

    public EntryType getEntryType() {
        return EntryType.MESSAGE;
    }
}
