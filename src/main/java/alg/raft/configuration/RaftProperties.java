package alg.raft.configuration;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RaftProperties {

    @Value("${raft.app.name:}")
    private String appName;
    // delimiter: ","
    @Value("${raft.cluster.nodes:}")
    private String nodes;

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

    @Value("${alg.raft.membership.configuration-on-rpc-error:true}")
    private boolean configurationOnRpcError;

    @PostConstruct
    public void init() {
        if (leaseInterval > electionMinTimeout) {
            throw new IllegalStateException("Lease interval should be lower than election minimum timeout.");
        }
    }

    public String getAppName() {
        return appName;
    }

    public String getNodes() {
        return nodes;
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

    public boolean configurationOnRpcError() {
        return configurationOnRpcError;
    }
}
