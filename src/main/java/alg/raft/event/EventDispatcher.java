package alg.raft.event;

import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class EventDispatcher {

    private Consumer<ReplicateEvent> replicateEventConsumer;
    private Consumer<LogApplyEvent> logApplyEventConsumer;
    private Consumer<ConfigurationInflightEvent> configurationInflightEventConsumer;

    public void registerReplicateEventConsumer(Consumer<ReplicateEvent> consumer) {
        this.replicateEventConsumer = consumer;
    }

    public void registerLogApplyEventConsumer(Consumer<LogApplyEvent> consumer) {
        this.logApplyEventConsumer = consumer;
    }

    public void registerConfigurationInflightEventConsumer(Consumer<ConfigurationInflightEvent> consumer) {
        this.configurationInflightEventConsumer = consumer;
    }

    public void dispatchReplicateEvent(ReplicateEvent event) {
        replicateEventConsumer.accept(event);
    }

    public void dispatchLogApplyEvent(LogApplyEvent event) {
        logApplyEventConsumer.accept(event);
    }

    public void dispatchConfigurationInflightEvent(ConfigurationInflightEvent event) {
        configurationInflightEventConsumer.accept(event);
    }
}
