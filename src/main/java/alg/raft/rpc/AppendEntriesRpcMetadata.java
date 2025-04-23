package alg.raft.rpc;

import alg.raft.AppendEntriesReq;
import alg.raft.Channel;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public record AppendEntriesRpcMetadata(
    Channel channel,
    AtomicInteger acknowledged,
    AppendEntriesReq request,
    int quorum,
    AtomicBoolean callbackTriggered
) {
}
