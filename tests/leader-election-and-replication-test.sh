#!/bin/bash
set -euo pipefail

bash ./tests/build.sh

SERVICES=(raft-node1 raft-node2 raft-node3)

echo 'Setup cluster...'
docker-compose up -d "${SERVICES[@]}"
sleep 15

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

LEADER=$(detect_leader)
if [[ -z "$LEADER" ]]; then
  echo "Leader not found!"
  exit 1
fi
echo "Current leader: $LEADER"

# insert a log
echo "Insert a log..."
ENTITY_ID=1
LEADER_CID=$(cid "$LEADER")
docker exec "$LEADER_CID" \
curl -XPUT \
-H "Content-Type: application/json" "http://localhost:8080/op" \
-d"{\"type\": \"PUT\", \"id\": $ENTITY_ID, \"entity\": {\"id\": $ENTITY_ID, \"value\": \"Hello, World!\"}}"
sleep 3

verify_replication() {
  for svc in "${SERVICES[@]}"; do
    CID=$(cid "$svc")
    LOG_LENGTH=$(docker exec "$CID" curl -s "http://localhost:8080/logs" | jq 'length')
    if [[ "$LOG_LENGTH" -ne 1 ]]; then
      echo "Failed to verify a number of logs in replica $svc"
      exit 1
    fi
  done
}

verify_replication
echo "Replication verified."

verify_local_statemachine() {
  for svc in "${SERVICES[@]}"; do
      CID=$(cid "$svc")
      VALUE=$(docker exec "$CID" curl -s "http://localhost:8080/get/$ENTITY_ID" | jq '.value')
      if [[ "$VALUE" != "\"Hello, World!\"" ]]; then
        echo "Failed to sync data for $svc"
        exit 1
      fi
    done
}

verify_local_statemachine
echo 'State machine is in-sync'

echo "Leader election & log replication test completed!"
bash ./tests/clean.sh