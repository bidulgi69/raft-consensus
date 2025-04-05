package alg.raft;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootApplication
public class RaftApplication {

    public static void main(String[] args) {
        SpringApplication.run(RaftApplication.class, args);
    }

    @Bean
    public WebClient webClient() {
        return WebClient.builder()
            .build();
    }

    @Bean
    public ObjectMapper mapper() {
        return new ObjectMapper();
    }
}
