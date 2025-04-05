package alg.raft.configuration;

import alg.raft.forremoval.HelloGrpcService;
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

    private final HelloGrpcService myGrpcServiceImpl;
    private final RaftGrpcService raftGrpcServiceImpl;

    public GrpcServerRunner(HelloGrpcService myGrpcServiceImpl,
                            RaftGrpcService raftGrpcServiceImpl
    ) {
        this.myGrpcServiceImpl = myGrpcServiceImpl;
        this.raftGrpcServiceImpl = raftGrpcServiceImpl;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        server = NettyServerBuilder.forPort(grpcPort)
            .addService(myGrpcServiceImpl)
            .addService(raftGrpcServiceImpl)
            .build()
            .start();
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }
}

