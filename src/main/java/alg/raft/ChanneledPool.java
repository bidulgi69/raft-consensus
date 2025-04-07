package alg.raft;

import alg.raft.utils.Magics;
import io.grpc.CompressorRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Component
public class ChanneledPool {

    private final ConcurrentMap<String, ManagedChannel> pool = new ConcurrentHashMap<>();

    public void connect(String url) {
        if (pool.containsKey(url)) {
            return;
        }

        ManagedChannel channel = ManagedChannelBuilder.forTarget(url)
            .idleTimeout(Magics.DEFAULT_HTTP_CLIENT_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .keepAliveTime(Magics.DEFAULT_HTTP_CLIENT_KEEP_ALIVE_TIME_SECONDS, TimeUnit.SECONDS)
            .compressorRegistry(CompressorRegistry.getDefaultInstance())
            .disableRetry()
            .usePlaintext()
            .build();

        pool.put(url, channel);
    }

    public void unpool(String url) {
        pool.remove(url);
    }

    public ManagedChannel getChannel(String url) {
        if (pool.containsKey(url)) {
            return pool.get(url);
        }

        connect(url);
        return pool.get(url);
    }
}
