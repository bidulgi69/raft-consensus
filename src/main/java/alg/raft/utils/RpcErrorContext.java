package alg.raft.utils;

import alg.raft.Channel;
import alg.raft.event.EventDispatcher;
import alg.raft.state.NodeState;
import io.grpc.StatusRuntimeException;

public record RpcErrorContext(
    Channel channel,
    StatusRuntimeException e,
    NodeState _stateRef,
    EventDispatcher _dispatcherRef
) {
}