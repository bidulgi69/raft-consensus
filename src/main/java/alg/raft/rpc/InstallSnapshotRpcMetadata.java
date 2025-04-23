package alg.raft.rpc;

import alg.raft.Channel;
import alg.raft.InstallSnapshotReq;

import java.util.concurrent.atomic.AtomicInteger;

public record InstallSnapshotRpcMetadata(
    Channel channel,
    AtomicInteger acknowledged,
    InstallSnapshotReq request
) {
}
