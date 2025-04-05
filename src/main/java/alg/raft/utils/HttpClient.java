package alg.raft.utils;

import alg.raft.enums.NodeType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static alg.raft.utils.Magics.DEFAULT_HTTP_PORT;

@Component
public class HttpClient {

    private final WebClient webClient;

    @Autowired
    public HttpClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<NodeType> getType(String node) {
        return webClient.get()
            .uri("http://" + node + ":" + DEFAULT_HTTP_PORT + "/type")
            .retrieve()
            .bodyToMono(NodeType.class)
            .onErrorResume(throwable -> Mono.just(NodeType.FOLLOWER));
    }

    public Mono<Void> join(String node, String appName, int port) {
        return webClient.patch()
            .uri("http://" + node + ":" + DEFAULT_HTTP_PORT + "/join?node=" + appName + "&port=" + port)
            .retrieve()
            .bodyToMono(Void.class);
    }
}
