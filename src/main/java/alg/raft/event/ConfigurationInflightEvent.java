package alg.raft.event;

import alg.raft.Channel;

public record ConfigurationInflightEvent(
    Channel zombieChannel
) {
}
