package alg.raft;

import alg.raft.db.LocalDatabase;
import alg.raft.enums.EntryType;
import alg.raft.enums.NodeType;
import alg.raft.event.EventDispatcher;
import alg.raft.event.LogApplyEvent;
import alg.raft.message.Configuration;
import alg.raft.message.ConfigurationType;
import alg.raft.message.Operation;
import alg.raft.state.NodeState;
import alg.raft.utils.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

// log 를 local statemachine 에 반영하는 작업을 처리
@Component
public class LogApplicator {

    private final NodeState state;
    private final Members members;
    private final LogManager logManager;
    private final LocalDatabase localDatabase;

    private final ExecutorService taskPool = Executors.newSingleThreadExecutor();
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public LogApplicator(NodeState state,
                         Members members,
                         LogManager logManager,
                         LocalDatabase localDatabase,
                         EventDispatcher eventDispatcher
    ) {
        this.state = state;
        this.members = members;
        this.logManager = logManager;
        this.localDatabase = localDatabase;
        eventDispatcher.registerLogApplyEventConsumer(scheduleLogApply());
    }

    public Consumer<LogApplyEvent> scheduleLogApply() {
        return event -> taskPool.execute(() -> applyLog(event));
    }

    private void applyLog(LogApplyEvent event) {
        long sequence = event.sequence();

        // 이미 반영한 로그는 처리하지 않음
        if (sequence <= state.getLastApplied()) {
            _logger.info("Log at {} is already processed.", sequence);
            return;
        }

        _logger.info("Log at {} is on processing.", sequence);
        switch (event.type()) {
            case CONFIGURATION -> {
                Configuration configuration = JsonMapper.readValue(event.log(), Configuration.class);
                if (configuration.type() == ConfigurationType.JOINT) {
                    members.jointMembership(configuration.newConfiguration());
                    if (NodeType.LEADER == state.getType()) {
                        _logger.info("Leader would confirm the configuration log at {}", sequence);
                        // enqueue new log
                        Configuration finalConfiguration = new Configuration(
                            ConfigurationType.FINAL,
                            null,
                            configuration.newConfiguration()
                        );
                        logManager.enqueue(EntryType.CONFIGURATION, finalConfiguration);
                    }
                } else if (configuration.type() == ConfigurationType.FINAL) {
                    members.updateMembership(configuration.newConfiguration());
                } else {
                    _logger.error("Log at {} is not a valid configuration.", sequence);
                }
            }
            case OPERATION -> {
                Operation op = JsonMapper.readValue(event.log(), Operation.class);
                switch (op.type()) {
                    case PUT -> localDatabase.put(op.entity());
                    case DELETE -> localDatabase.delete(op.id());
                }
            }
        }
        state.setLastApplied(sequence);
    }
}
