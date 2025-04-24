#!/bin/bash
SERVICES=(raft-node1 raft-node2 raft-node3)
cid() { docker-compose ps -q "$1"; }
detect_leader() {
  for svc in "${SERVICES[@]}"; do
    CID=$(cid "$svc")
    role=$(docker exec "$CID" curl -s "http://localhost:8080/type")
    if [[ "$role" == "\"LEADER\"" ]]; then
      echo "$svc"
      return
    fi
  done
  echo ""
}

bash ./tests/build.sh

echo 'Setup cluster...'
docker-compose up -d "${SERVICES[@]}"
sleep 15

LEADER=$(detect_leader)
if [[ -z "$LEADER" ]]; then
  echo "Leader not found!"
  exit 1
fi
echo "Current leader: $LEADER"

generate_log() {
  local log_size=$1
  CID=$(cid "$LEADER")
  for (( index=1; index<="$log_size"; index++ )); do
    docker exec "$CID" curl -s -XPUT "http://localhost:8080/op" \
      -H "Content-Type: application/json" \
      -d"{\"type\": \"PUT\", \"id\": $index, \"entity\": {\"id\": $index, \"value\": \"Message at index $index\"}}"
  done
}

# generate 100 logs
LOG_SIZE=100
echo "Generate logs!"
generate_log "$LOG_SIZE"

# Wait until the nodes create own snapshot
echo "Wait until each nodes create own snapshot..."
sleep 20

# Check snapshot is created
verify_log_size_inmemory() {
  for svc in "${SERVICES[@]}"; do
      CID=$(cid "$svc")
      LOG_LENGTH=$(docker exec "$CID" curl -s "http://localhost:8080/logs" | jq 'length')
      if [[ "$LOG_LENGTH" -ne 0 ]]; then
        echo "Failed to verify a number of logs in replica $svc"
        exit 1
      fi
    done
}

verify_local_statemachine() {
  local -a nodes=()
  nodes=("${@}")
  for svc in "${nodes[@]}"; do
    CID=$(cid "$svc")
    for (( index=1; index<="$LOG_SIZE"; index++ )); do
      VALUE=$(docker exec "$CID" curl -s "http://localhost:8080/get/$index")
      if [[ -z "$VALUE" ]]; then
        echo "Failed to verify local statemachine."
        exit 1
      fi
    done
  done
}

verify_log_size_inmemory
verify_local_statemachine "${SERVICES[@]}"
echo 'Verified that every nodes have own snapshot!'

# Because the logs in memory have been emptied,
# AppendEntriesRPC cannot be used to catch up with the state when a new node joins.
# In order for the new node to catch up,
# an InstallSnapshot must be applied,
# So we can verify that an InstallSnapshot was used if the states of statemachine are same.
NEW_NODE="raft-node4"
echo "A new node($NEW_NODE) joins the cluster."
docker-compose up -d "$NEW_NODE"
# wait catch-up
echo "Wait the $NEW_NODE catches up..."
sleep 10

verify_local_statemachine "$NEW_NODE"
echo "Verified that the statemachine of $NEW_NODE is same with other nodes!"

echo "Log compaction and catch-up(installSnapshotRPC) test completed!"
bash ./tests/clean.sh
