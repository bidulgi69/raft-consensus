package alg.raft.configuration;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RaftProperties {

    @Value("${alg.raft.heartbeat-interval-ms:1000}")
    private int leaseInterval;

    @Value("${alg.raft.election.min-timeout-ms:4000}")
    private int electionMinTimeout;

    @Value("${alg.raft.election.max-timeout-ms:10000}")
    private int electionMaxTimeout;

    @Value("${alg.raft.compaction.threshold:100}")
    private int compactionThreshold;

    @Value("${alg.raft.compaction.init-delay-ms:500}")
    private int compactionInitDelay;

    @Value("${alg.raft.compaction.interval-ms:5000}")
    private int compactionInterval;

    @PostConstruct
    public void init() {
        if (leaseInterval > electionMinTimeout) {
            throw new IllegalStateException("Lease interval should be lower than election minimum timeout.");
        }
    }

    public int getLeaseInterval() {
        return leaseInterval;
    }

    public int getElectionMinTimeout() {
        return electionMinTimeout;
    }

    public int getElectionMaxTimeout() {
        return electionMaxTimeout;
    }

    public int getCompactionThreshold() {
        return compactionThreshold;
    }

    public int getCompactionInitDelay() {
        return compactionInitDelay;
    }

    public int getCompactionInterval() {
        return compactionInterval;
    }
}
