package alg.raft;

import alg.raft.enums.EntryType;
import alg.raft.state.NodeState;
import alg.raft.utils.JsonMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LogManager {

    private final Members members;
    private final NodeState state;
    private final LogTaskProcessor logTaskProcessor;

    // disk 에 관리돼야 함
    private final List<LogEntry> entries = new ArrayList<>();
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public LogManager(Members members,
                      NodeState state,
                      LogTaskProcessor logTaskProcessor
    ) {
        this.members = members;
        this.state = state;
        this.logTaskProcessor = logTaskProcessor;
    }

    public <T> void enqueue(EntryType type, T log) {
        final int size = entries.size();
        final LogEntry entry = new LogEntry(size, state.getCurrentTerm(), type, JsonMapper.writeValueAsString(log));
        entries.add(entry);
        final LogEntry prev = size > 1 ? entries.get(size - 1) : null;

        AtomicInteger committed = new AtomicInteger(1); // 자기 자신은 동의를 한채로 시작
        int quorum = 1 + ((members.getActiveChannelCount()+1) / 2);

        Mono.fromRunnable(() -> members.getActiveChannels()
            .parallelStream()
            .forEach(channel -> {
                RaftServiceGrpc.RaftServiceStub stub = RaftServiceGrpc.newStub(channel.channel());
                final int nextIndex = (int) state.getNextIndex(channel.id());
                final int lastIndex = Math.min(nextIndex + 100, entries.size());
                // 한번에 최대 100개의 로그를 전송
                List<Entry> logs = entries.subList(nextIndex, lastIndex)
                    .stream()
                    .map(le -> Entry.newBuilder()
                        .setSequence(le.sequence())
                        .setTerm(le.term())
                        .setTypeValue(le.type().ordinal())
                        .setLog(le.log())
                        .build()
                    )
                    .toList();

                AppendEntriesReq req = AppendEntriesReq.newBuilder()
                    .setTerm(state.getCurrentTerm())
                    .setLeaderId(state.getAppId())
                    .setPrevLogIndex(prev != null ? prev.sequence() : -1)
                    .setPrevLogTerm(prev != null ? prev.term() : state.getCurrentTerm())
                    .addAllEntries(logs)
                    .setLeaderCommit(state.getCommitIndex())// 현재 node 의 committedQueue 의 index
                    .build();

                // callback
                StreamObserver<AppendEntriesResp> observer = new StreamObserver<>() {
                    @Override
                    public void onNext(AppendEntriesResp resp) {
                        if (resp.getSuccess()) {
                            Entry lastLogEntry = logs.get(logs.size() - 1);
                            state.setMatchIndex(channel.id(), lastLogEntry.getSequence());
                            state.setNextIndex(channel.id(), lastLogEntry.getSequence()+1);
                            // quorum 이상의 동의를 얻은 경우 commit 처리
                            if (committed.incrementAndGet() >= quorum) {
                                state.setCommitIndex(Math.max(state.getCommitIndex(), size));
                                // local state machine 에 반영
                                logs.forEach(logTaskProcessor::registerTask);
                            }
                        } else {
                            // entry 정보가 동일할 때까지 반복
                            state.setNextIndex(channel.id(), state.getNextIndex(channel.id())-1);
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        _logger.error("An error occurred on AppendEntries RPC({} -> {})",
                            state.getAppId(),
                            channel.id(),
                            throwable
                        );
                    }

                    @Override
                    public void onCompleted() {
                        // do nothing
                    }
                };

                // rpc 전송
                stub.appendEntries(req, observer);
            }))
            .subscribe();
    }

    public void replicate(long leaderCommit) {
        // 5. If leaderCommit > commitIndex, set commitIndex =
        // min(leaderCommit, index of last new entry)
        if (leaderCommit > state.getCommitIndex()) {
            state.setCommitIndex(Math.min(leaderCommit, state.getCommitIndex()));
        }
    }

    public void replicate(Entry entry) {
        // replicate log entries
        entries.add(new LogEntry(
            entry.getSequence(),
            entry.getTerm(),
            EntryType.valueOf(entry.getType().name()),
            entry.getLog()
        ));
    }

    public Iterable<LogEntry> getCommitedEntries() {
        return entries;
    }

    public LogEntry getEntry(int index) {
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        return entries.get(index);
    }

    public List<LogEntry> getEntries(int fromIndex, int toIndex) {
        if (fromIndex > toIndex
            || fromIndex >= entries.size()
        ) {
            return Collections.emptyList();
        }
        return entries.subList(fromIndex, Math.min(toIndex, entries.size()));
    }

    public LogEntry getLastEntry() {
        return entries.isEmpty() ?
            null :
            entries.get(entries.size() - 1);
    }

    public void deleteEntriesFromIndex(int index) {
        if (entries.size() > index) {
            entries.subList(index, entries.size()).clear();
        }
    }
}
