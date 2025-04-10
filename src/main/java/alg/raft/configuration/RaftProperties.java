package alg.raft.configuration;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RaftProperties {

    @Value("${alg.raft.heartbeat.interval.ms:1000}")
    private int leaseInterval;

    @Value("${alg.raft.election.min-timeout.ms:4000}")
    private int electionMinTimeout;

    @Value("${alg.raft.election.max-timeout.ms:10000}")
    private int electionMaxTimeout;

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
}
