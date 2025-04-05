package alg.raft;

import alg.raft.enums.EntryType;
import alg.raft.message.Configuration;
import alg.raft.state.NodeState;
import alg.raft.utils.JsonMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class LogTaskProcessor {

    private final NodeState state;
    private final Members members;

    private final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public LogTaskProcessor(NodeState state,
                            Members members
    ) {
        this.state = state;
        this.members = members;
    }

    @PostConstruct
    public void init() {
        executor.execute(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 큐에서 task를 꺼내어 실행 (큐가 비면 대기)
                    Runnable task = taskQueue.take();
                    task.run();
                } catch (InterruptedException e) {
                    // 인터럽트 발생 시 스레드 종료
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // 작업 실행 중 예외가 발생해도 worker 스레드가 종료되지 않도록 처리
                    _logger.error(e.getMessage(), e);
                }
            }
        });
    }

    public void registerTask(EntryType type, int sequence, String log) {
        boolean offered = taskQueue.offer(() -> {
            // 이미 반영한 로그는 처리하지 않음
            if (sequence < state.getLastApplied()) {
                _logger.info("Log at {} is already processed.", sequence);
                return;
            }

            _logger.info("Log at {} is on processing.", sequence);
            switch (type) {
                case CONFIGURATION -> {
                    Configuration configuration = JsonMapper.readValue(log, Configuration.class);
                    Set<String> oldConfiguration = configuration.oldConfiguration();
                    Set<String> newConfiguration = configuration.newConfiguration();
                    //todo membership 변경 작업 처리
                }
                default -> {
                    //do nothing
                }
            }
            state.setLastApplied(sequence);
        });

        if (!offered) {
            _logger.warn("Failed to emit log at {}", sequence);
        }
    }

    public void registerTask(LogEntry entry) {
        registerTask(entry.type(), entry.sequence(), entry.log());
    }

    public void registerTask(Entry entry) {
        registerTask(EntryType.valueOf(entry.getType().name()), entry.getSequence(), entry.getLog());
    }
}
