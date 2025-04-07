package alg.raft;

import alg.raft.enums.Membership;
import alg.raft.state.NodeState;
import alg.raft.utils.Magics;
import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class Members {

    @Value("${raft.app.name:}")
    private String appName;
    // delimiter: ","
    @Value("${raft.cluster.nodes:}")
    private String nodes;

    private AtomicReference<Membership> membership;

    private final NodeState state;
    private final ChanneledPool channelPool;
    private Map<String, Channel> activeChannels; // members
    private Map<String, Channel> jointChannels; // joint membership
    private final Logger _logger = LoggerFactory.getLogger(getClass());

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
        jointChannels = new ConcurrentHashMap<>();
        membership = new AtomicReference<>(Membership.STABLE);
    }

    public int getActiveChannelCount() {
        if (Membership.JOINT == membership.get()) {
            return 1 + jointChannels.size();
        } else {
            return 1 + activeChannels.size();
        }
    }

    public Collection<Channel> getActiveChannels() {
        return Membership.JOINT == membership.get() ?
            jointChannels.values() :
            activeChannels.values();
    }

    public Set<String> getActiveChannelHosts() {
        Set<String> activeChannelHosts = new HashSet<>(
            Membership.JOINT == membership.get() ?
                jointChannels.keySet() :
                activeChannels.keySet());
        activeChannelHosts.add(appName);

        return activeChannelHosts;
    }

    public void jointMembership(Set<String> newConfiguration) {
        for (String node : newConfiguration) {
            if (!jointChannels.containsKey(node) && !appName.equals(node)) {
                Channel channel = Optional.ofNullable(activeChannels.getOrDefault(node, null))
                    .orElseGet(() -> {
                        String rpcConnect = node + ":" + Magics.DEFAULT_RPC_PORT;
                        ManagedChannel managedChannel = channelPool.getChannel(rpcConnect);
                        return new Channel(
                            node,
                            Magics.DEFAULT_RPC_PORT,
                            managedChannel
                        );
                    });

                jointChannels.put(node, channel);
            }
        }
        membership.set(Membership.JOINT);
    }

    public void updateMembership(Set<String> newConfiguration) {
        newConfiguration.remove(appName);
        for (String node : newConfiguration) {
            if (!activeChannels.containsKey(node) && jointChannels.containsKey(node)) {
                _logger.info("New node {} joins in the membership", node);
                Channel channel = jointChannels.get(node);
                activeChannels.put(node, channel);
                jointChannels.remove(node);
            } else if (activeChannels.containsKey(node)) {
                jointChannels.remove(node);
            }
        }

        for (String node : activeChannels.keySet()) {
            if (!newConfiguration.contains(node)) {
                _logger.info("Remove existing node {} from active connections", node);
                // cleanup the connection
                Channel _remove = activeChannels.get(node);
                _remove.channel().shutdown();
                channelPool.unpool(_remove.id());
                // log 저장소가 휘발성 메모리이므로, 원활한 catch-up 을 위해 인덱스를 초기화 해준다.
                state.removeNextIndex(_remove.id());
                state.removeMatchIndex(_remove.id());
                activeChannels.remove(node);
            }
        }

        membership.set(Membership.STABLE);
        if (!jointChannels.isEmpty()) {
            _logger.warn("An anomaly was detected after the membership update.");
            jointChannels.clear();
        }
    }

    public Membership getMembership() {
        return membership.get();
    }
}
