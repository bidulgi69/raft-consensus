package alg.raft.api;

import alg.raft.Channel;
import alg.raft.LogManager;
import alg.raft.Members;
import alg.raft.enums.EntryType;
import alg.raft.enums.NodeType;
import alg.raft.message.Configuration;
import alg.raft.message.ConfigurationType;
import alg.raft.message.LogEntry;
import alg.raft.message.Operation;
import alg.raft.state.NodeState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
public class OperationController {

    private final LogManager logManager;
    private final Members members;
    private final NodeState nodeState;

    @Autowired
    public OperationController(LogManager logManager,
                               Members members,
                               NodeState nodeState
    ) {
        this.logManager = logManager;
        this.members = members;
        this.nodeState = nodeState;
    }

    @PutMapping("/op")
    public void op(@RequestBody Operation operation) {
        if (NodeType.LEADER.equals(nodeState.getType())) {
            logManager.enqueue(EntryType.OPERATION, operation);
        }
    }

    @PatchMapping("/join")
    public void join(@RequestParam String node) {
        Set<String> oldConfiguration = members.getActiveChannelHosts();
        Set<String> newConfiguration = new HashSet<>(oldConfiguration);
        newConfiguration.add(node);
        if (oldConfiguration.size() == newConfiguration.size()) {
            return;
        }

        Configuration configuration = new Configuration(
            ConfigurationType.JOINT,
            oldConfiguration,
            newConfiguration
        );
        logManager.enqueue(EntryType.CONFIGURATION, configuration);
    }

    @DeleteMapping("/leave")
    public void leave(@RequestParam String node) {
        Set<String> oldConfiguration = members.getActiveChannelHosts();
        Set<String> newConfiguration = new HashSet<>(oldConfiguration);
        newConfiguration.remove(node);
        if (oldConfiguration.size() == newConfiguration.size()) {
            return;
        }

        Configuration configuration = new Configuration(
            ConfigurationType.JOINT,
            oldConfiguration,
            newConfiguration
        );
        logManager.enqueue(EntryType.CONFIGURATION, configuration);
    }

    @GetMapping("/logs")
    public Iterable<LogEntry> getLogs() {
        // returns entire entries
        return logManager.getCommitedEntries();
    }

    @GetMapping("/statez")
    public String getState() {
        return nodeState.toString();
    }

    @GetMapping("/type")
    public NodeType getType() {
        return nodeState.getType();
    }

    @GetMapping("/membership")
    public String getMembership() {
        List<String> hosts = members.getActiveChannels()
            .stream()
            .map(Channel::host)
            .toList();

        return """
            [Membership]
            | type: %s
            | members: %s
            """
            .formatted(
                members.getMembership(),
                hosts
            );
    }
}
