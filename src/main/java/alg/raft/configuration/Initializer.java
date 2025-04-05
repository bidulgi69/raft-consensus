package alg.raft.configuration;

import alg.raft.Channel;
import alg.raft.ElectionManager;
import alg.raft.Members;
import alg.raft.enums.NodeType;
import alg.raft.utils.HttpClient;
import alg.raft.utils.Magics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class Initializer implements CommandLineRunner {

    @Value("${raft.app.name:}")
    private String appName;

    private final Members members;
    private final HttpClient httpClient;
    private final ElectionManager electionManager;

    @Autowired
    public Initializer(Members members,
                       HttpClient httpClient,
                       ElectionManager electionManager
    ) {
        this.members = members;
        this.httpClient = httpClient;
        this.electionManager = electionManager;
    }

    @Override
    public void run(String... args) {
        // 클러스터에 리더가 존재하는 경우 join 요청
        for (Channel channel : members.getActiveChannels()) {
            NodeType type = httpClient.getType(channel.host()).block();
            if (NodeType.LEADER == type) {
                httpClient.join(channel.host(), appName, Magics.DEFAULT_RPC_PORT).block();
                return;
            }
        }
        // 클러스터에 리더가 존재하지 않는 경우
        electionManager.reschedule();
    }
}