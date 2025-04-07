package alg.raft.utils;

import alg.raft.Channel;
import alg.raft.LogManager;
import alg.raft.Members;
import alg.raft.state.NodeState;
import io.grpc.StatusRuntimeException;

public record RpcErrorContext(
    Channel channel,
    StatusRuntimeException e,
    NodeState _stateRef,
    Members _membersRef,
    LogManager _logManagerRef
) {
}