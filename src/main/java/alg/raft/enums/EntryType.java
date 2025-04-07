package alg.raft.enums;

public enum EntryType {

    MESSAGE(false),
    CONFIGURATION(true)
    ;

    private final boolean jointConsensus;

    EntryType(boolean jointConsensus) {
        this.jointConsensus = jointConsensus;
    }

    public boolean isJointConsensus() {
        return jointConsensus;
    }
}
