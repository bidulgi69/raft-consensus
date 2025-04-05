package alg.raft;

import io.grpc.ManagedChannel;

public record Channel(
    String host,
    int port,
    ManagedChannel channel
) {

    public String id() {
        return host + ":" + port;
    }
}