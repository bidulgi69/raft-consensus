# Raft-Consensus
It is a toy project that implements the Raft consensus algorithm. The project is designed to help you learn and experiment with key concepts of the Raft algorithm, such as leader election, log replication, and configuration changes in a distributed system.

---

## Overview
Raft is an algorithm designed to make complex consensus processes in distributed systems easier to understand.<br> The project includes the following features

### Leader Election
Maintains cluster consistency by electing a single leader, even in the face of network partitions and failures.

### Log Replication
The leader records client commands to a log, which is then replicated to followers via the AppendEntries RPC and committed upon majority approval.

### Membership Change
Dynamically change the cluster configuration by securely adding/removing nodes through a joint consensus approach.

---

## Quick Start
You can try running multiple nodes simultaneously using the docker-compose.yml file included in the project.
```shell
# run with 3 nodes
docker-compose up init-cluster
```

You can also experiment with leader election and membership change by creating and stopping docker containers.
```shell
# a new node joins the cluster
docker-compose up -d raft-node4

# raise unexpected server failure
docker-compose down raft-node1

# rejoin
docker-compose up -d raft-node1
```

You can experiment log replication behavior through the following REST APIs.
```shell
# make sure you request to the leader node.
curl -XPUT {leader_node}/put -d '{"message": "Hello World!"}' -i

# Verify that the above log have been replicated to all nodes
curl -XGET {any_node}/logs -s
```


## Reference
https://raft.github.io/raft.pdf