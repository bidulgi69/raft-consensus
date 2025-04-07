package alg.raft.configuration;

import alg.raft.Channel;
import alg.raft.ElectionManager;
import alg.raft.Members;
import alg.raft.enums.NodeType;
import alg.raft.utils.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Initializer {

    @Value("${raft.app.name:}")
    private String appName;

    private final Members members;
    private final HttpClient httpClient;
    private final ElectionManager electionManager;
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public Initializer(Members members,
                       HttpClient httpClient,
                       ElectionManager electionManager
    ) {
        this.members = members;
        this.httpClient = httpClient;
        this.electionManager = electionManager;
    }

    public void run() {
        // 클러스터에 리더가 존재하는 경우 join 요청
        for (Channel channel : members.getActiveChannels()) {
            NodeType type = httpClient.getType(channel.host()).block();
            _logger.info("Node({})'s type is: {}", channel.host(), type);
            if (NodeType.LEADER == type) {
                httpClient.join(channel.host(), appName).block();
                return;
            }
        }
        // 클러스터에 리더가 존재하지 않는 경우
        _logger.info("There is no active leader in the cluster.");
        electionManager.reschedule();
    }
}