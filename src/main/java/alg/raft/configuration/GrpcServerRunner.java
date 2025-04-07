package alg.raft.configuration;

import alg.raft.rpc.RaftGrpcService;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;

@Component
public class GrpcServerRunner implements ApplicationRunner {

    @Value("${grpc.server.port:9090}")
    private int grpcPort;

    private Server server;

    // grpc 서버 실행 이후 처리를 위한 DI
    private final Initializer initializer;
    private final RaftGrpcService raftGrpcServiceImpl;

    public GrpcServerRunner(Initializer initializer,
                            RaftGrpcService raftGrpcServiceImpl
    ) {
        this.initializer = initializer;
        this.raftGrpcServiceImpl = raftGrpcServiceImpl;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        server = NettyServerBuilder.forPort(grpcPort)
            .addService(raftGrpcServiceImpl)
            .build()
            .start();

        initializer.run();
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }
}

