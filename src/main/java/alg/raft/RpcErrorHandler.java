package alg.raft;

import alg.raft.enums.EntryType;
import alg.raft.enums.NodeType;
import alg.raft.message.Configuration;
import alg.raft.message.ConfigurationType;
import alg.raft.utils.RpcErrorContext;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public final class RpcErrorHandler {

    private static final Logger _logger = LoggerFactory.getLogger(RpcErrorHandler.class);
    private static final Set<Status.Code> KICK_OUT_GRPC_RESPONSE_STATUS = EnumSet.of(
        Status.Code.UNAVAILABLE,
        Status.Code.DEADLINE_EXCEEDED
    );

    static void handleRpcError(RpcErrorContext context) {
        if (NodeType.LEADER == context._stateRef().getType()
            && KICK_OUT_GRPC_RESPONSE_STATUS.contains(context.e().getStatus().getCode())
        ) {
            Set<String> oldConfiguration = context._membersRef().getActiveChannelHosts();
            Set<String> newConfiguration = new HashSet<>(oldConfiguration);
            newConfiguration.remove(context.channel().host());
            Configuration configuration = new Configuration(
                ConfigurationType.JOINT,
                oldConfiguration,
                newConfiguration
            );
            context._logManagerRef().enqueue(EntryType.CONFIGURATION, configuration);
        } else {
            _logger.error("An error occurred on rpc transportation: {}", context.e().getMessage());
        }
        context.channel().channel().enterIdle();
    }
}
