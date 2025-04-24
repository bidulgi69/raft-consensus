#!/bin/bash
SERVICES=(raft-node1 raft-node2 raft-node3)

cid() { docker-compose ps -q "$1"; }

bash ./tests/build.sh

echo "Setup cluster..."
docker-compose up -d "${SERVICES[@]}"
sleep 15

# run a new node(raft-node4)
NEW_NODE="raft-node4"
echo "A new node($NEW_NODE) joins the cluster."
docker-compose up -d "$NEW_NODE"
sleep 7

# verify configuration logs are replicated through nodes
verify_replication() {
  local expected_length=$1
  shift # shift argument pointer
  local -a nodes=()
  # accept leftovers
  nodes=("${@}")
  for svc in "${nodes[@]}"; do
    CID=$(cid "$svc")
    LOG_LENGTH=$(docker exec "$CID" curl -s "http://localhost:8080/logs" | jq 'length')
    if [[ "$LOG_LENGTH" -ne "$expected_length" ]]; then
      echo "Failed to verify a number of logs in replica $svc"
      exit 1
    fi
  done
}

verify_membership_1() {
  local should_exist_node=$1
  shift
  local -a nodes=()
  nodes=("${@}")
  for svc in "${nodes[@]}"; do
    CID=$(cid "$svc")
    NODE_EXISTS_IN_MEMBERSHIP=$(docker exec "$CID" curl -s "http://localhost:8080/membership" | grep "$should_exist_node")
    if [[ -z "$NODE_EXISTS_IN_MEMBERSHIP" ]]; then
      echo "Failed to verify on node($svc), $should_exist_node doesn't exist in the membership."
      exit 1
    fi
  done
}

NEW_SERVICES+=("${SERVICES[@]}" "$NEW_NODE")
# 2 = JOINT, FINAL logs
verify_replication 2 "${NEW_SERVICES[@]}"
verify_membership_1 "$NEW_NODE" "${SERVICES[@]}"
echo "Verified membership change and replication when a node joins."

# when a node leaves
echo "Node($NEW_NODE) leaves."
docker-compose down "$NEW_NODE"
sleep 10

verify_membership_2() {
  local non_existing_node=$1
  shift
  local -a nodes=()
  nodes=("${@}")
  for svc in "${nodes[@]}"; do
    CID=$(cid "$svc")
    NODE_EXISTS_IN_MEMBERSHIP=$(docker exec "$CID" curl -s "http://localhost:8080/membership" | grep "$non_existing_node")
    if [[ -n "$NODE_EXISTS_IN_MEMBERSHIP" ]]; then
      echo "Failed to verify on node($svc), $non_existing_node still exists in the membership."
      exit 1
    fi
  done
}

# 4 = existing + JOINT, FINAL logs
verify_replication 4 "${SERVICES[@]}"
verify_membership_2 "$NEW_NODE" "${SERVICES[@]}"
echo "Verified membership change and replication when a node leaves."

echo "Membership change test completed!"
bash ./tests/clean.sh