package alg.raft;

import alg.raft.state.NodeState;
import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class Members {

    @Value("${raft.app.name:}")
    private String appName;
    // delimiter: ","
    @Value("${raft.cluster.nodes:}")
    private String nodes;

    private final NodeState state;
    private final ChanneledPool channelPool;
    private Map<String, Channel> activeChannels;
    private Map<String, Channel> inactiveChannels;

    @Autowired
    public Members(NodeState state,
                   ChanneledPool channelPool
    ) {
        this.state = state;
        this.channelPool = channelPool;
        this.activeChannels = new ConcurrentHashMap<>();
    }

    @PostConstruct
    public void init() {
        if (nodes.isBlank()) {
            throw new IllegalStateException("Node list should not be blank.");
        }

        List<String> topology = Arrays.stream(nodes.split(",")).collect(Collectors.toList());
        int appId = -1;
        for (int i = 0; i < topology.size(); i++) {
            if (topology.get(i).contains(appName)) {
                appId = i;
                break;
            }
        }

        assert appId != -1;
        topology.remove(appId);

        state.setAppId(appId);
        activeChannels = new ConcurrentHashMap<>(
            topology.stream()
                .map(tp -> {
                    ManagedChannel channel = channelPool.getChannel(tp);
                    String[] sp = tp.split(":");
                    return new Channel(sp[0], Integer.parseInt(sp[1]), channel);
                })
                .collect(Collectors.toMap(
                    Channel::host,
                    Function.identity()
                ))
        );
        inactiveChannels = new ConcurrentHashMap<>();
    }

    public boolean exist(String node) {
        return activeChannels.containsKey(node);
    }

    public int getActiveChannelCount() {
        return activeChannels.size();
    }

    public Collection<Channel> getActiveChannels() {
        return activeChannels.values();
    }

    public Collection<Channel> getInactiveChannels() {
        return inactiveChannels.values();
    }

    public Set<String> getActiveChannelHosts() {
        return activeChannels.keySet();
    }

    public Channel join(String node, int port) {
        if (node.equals(appName)) {
            return null;
        }

        Channel channel = inactiveChannels.getOrDefault(
            node,
            new Channel(node, port, channelPool.getChannel(node))
        );
        inactiveChannels.remove(node);
        activeChannels.put(node, channel);

        return channel;
    }

    public void leave(String node) {
        if (node.equals(appName)) {
            return;
        }

        Channel channel = activeChannels.get(node);
        if (channel != null) {
            channel.channel().enterIdle();
            activeChannels.remove(node);
            inactiveChannels.put(node, channel);
        }
    }
}
