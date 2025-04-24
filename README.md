# Raft-Consensus
A lightweight Java implementation of the Raft consensus algorithm. This project is designed to help you learn and experiment with key concepts of the Raft protocol, such as leader election, log replication & compaction, and configuration changes in a distributed system.

---

## Overview
Raft is an algorithm designed to make complex consensus processes in distributed systems easier to understand.<br> The project includes the following features

### 1. Leader Election
Follows Raft spec with randomized election timeouts to elect a single leader.

### 2. Log Replication
Commands are recorded as LogEntry objects and appended to followers via AppendEntries RPC.

### 3. Snapshot
Once the log grows beyond a threshold, a binary snapshot of the state machine is created and old logs are truncated.  New or lagging nodes catch up via InstallSnapshot RPC.

### 4. Membership Changes
Add or remove nodes safely by replicating configuration-change entries.

### 5. Fault Tolerance
Supports leader failover, network partitions (using iptables in tests), and recovery from crashes.

### ...Testing Suite
Automated bash scripts under tests/ orchestrate multi-node clusters with Docker Compose and validate:
- Leader election & replication
- Failover and rejoin behavior
- Dynamic join/leave
- Snapshot generation and catch-up via snapshot RPC
- Network partition scenarios

---

## Testing
Scripts in the /tests directory drives a series of scenarios:

### 1. Leader election and replication 
Test the process of electing a leader among the nodes and replicating logs from the leader to the follower nodes.

### 2. Leader broke(failover)
Test the failover process in which a new node is elected and configuration (membership changes) are handled when the leader node fails.

### 3. Membership change
Test that the configuration log is replicated and reflected in each local membership by testing new nodes joining and existing nodes leaving.

### 4. Snapshot compaction
A snapshot is created on each node as soon as the number of logs crosses a threshold, and nodes that requested logs earlier than the `lastIncludedIndex` in the snapshot are tested via `InstallSnapshot RPC` to see if a follower can catch-up the current state.

### 5. Network partition and recovery
Test production-likely case like network partitioning using iptables. After partitioning networks between leader and follower group, a new leader node would have been elected in the follower group. And the old-leader node would be a follower node when the network is restored.

---

## Reference
https://raft.github.io/raft.pdf
