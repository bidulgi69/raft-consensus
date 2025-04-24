package alg.raft;

import alg.raft.configuration.RaftProperties;
import alg.raft.enums.EntryType;
import alg.raft.enums.Membership;
import alg.raft.event.ConfigurationInflightEvent;
import alg.raft.event.EventDispatcher;
import alg.raft.message.Configuration;
import alg.raft.message.ConfigurationType;
import alg.raft.state.NodeState;
import alg.raft.utils.Magics;
import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class Members {
    private AtomicReference<Membership> membership;
    private AtomicReference<Configuration> inflightConfiguration = new AtomicReference<>();

    private final NodeState state;
    private final ChanneledPool channelPool;
    private final LogManager logManager;
    private final RaftProperties properties;
    private Map<String, Channel> activeChannels; // members
    private Map<String, Channel> jointChannels; // joint membership
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public Members(NodeState state,
                   ChanneledPool channelPool,
                   LogManager logManager,
                   RaftProperties properties,
                   EventDispatcher eventDispatcher
    ) {
        this.state = state;
        this.channelPool = channelPool;
        this.logManager = logManager;
        this.properties = properties;
        this.activeChannels = new ConcurrentHashMap<>();
        eventDispatcher.registerConfigurationInflightEventConsumer(doConfigurationChange());
    }

    @PostConstruct
    public void init() {
        if (properties.getAppName().isBlank()) {
            throw new IllegalStateException("Node list should not be blank.");
        }

        List<String> topology = Arrays.stream(properties.getNodes().split(",")).collect(Collectors.toList());
        int appId = -1;
        for (int i = 0; i < topology.size(); i++) {
            if (topology.get(i).contains(properties.getAppName())) {
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
        activeChannelHosts.add(properties.getAppName());

        return activeChannelHosts;
    }

    public void jointMembership(Set<String> newConfiguration) {
        for (String node : newConfiguration) {
            if (!jointChannels.containsKey(node) && !properties.getAppName().equals(node)) {
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
        _logger.info("Node runs on joint mode...");
    }

    public void updateMembership(Set<String> newConfiguration) {
        newConfiguration.remove(properties.getAppName());
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
        inflightConfiguration = new AtomicReference<>();
        if (!jointChannels.isEmpty()) {
            _logger.warn("An anomaly was detected after the membership update.");
            jointChannels.clear();
        }
        _logger.info("Membership update is completed via {}", newConfiguration);
    }

    public Membership getMembership() {
        return membership.get();
    }

    private Consumer<ConfigurationInflightEvent> doConfigurationChange() {
        return event -> {
            // rpc 오류와 configuration 로그를 관계하지 않는다.
            if (!properties.configurationOnRpcError()) {
                return;
            }

            Set<String> oldConfiguration = getActiveChannelHosts();
            Set<String> newConfiguration = new HashSet<>(oldConfiguration);
            newConfiguration.remove(event.zombieChannel().host());
            Configuration configuration = new Configuration(
                ConfigurationType.JOINT,
                oldConfiguration,
                newConfiguration
            );
            // 멤버쉽에 변경이 없는 경우 무시한다.
            if (oldConfiguration.equals(newConfiguration)) {
                return;
            }
            // 동일한 membership 변경이 진행 중인 경우 무시한다.
            if (inflightConfiguration.get() != null &&
                oldConfiguration.equals(inflightConfiguration.get().oldConfiguration()) &&
                newConfiguration.equals(inflightConfiguration.get().newConfiguration())
            ) {
                return;
            }

            inflightConfiguration.set(configuration);
            logManager.enqueue(EntryType.CONFIGURATION, configuration);
        };
    }
}
