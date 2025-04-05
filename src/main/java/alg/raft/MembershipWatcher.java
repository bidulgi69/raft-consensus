package alg.raft;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MembershipWatcher {

    private final Members members;
//    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
//    private ScheduledFuture<?> scheduledFuture;

    @Autowired
    public MembershipWatcher(Members members) {
        this.members = members;
    }

    //todo inactive channel 을 감시하다가 살아있는 상태가 되면 그룹에 join 시킨다
    public void watch() {
        for (Channel channel : members.getInactiveChannels()) {
            RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(channel.channel());

        }
    }
}
