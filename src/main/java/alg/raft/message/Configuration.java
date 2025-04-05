package alg.raft.message;

import java.util.Set;

public record Configuration(
    ConfigurationType type,
    Set<String> oldConfiguration,
    Set<String> newConfiguration
) {
}
