package alg.raft;

import alg.raft.enums.NodeType;
import alg.raft.event.ConfigurationInflightEvent;
import alg.raft.utils.RpcErrorContext;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
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
            context._dispatcherRef().dispatchConfigurationInflightEvent(new ConfigurationInflightEvent(context.channel()));
        } else {
            _logger.error("An error occurred on rpc transportation: {}", context.e().getMessage());
        }
        context.channel().channel().enterIdle();
    }
}
