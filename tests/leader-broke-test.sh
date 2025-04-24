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

# terminate leader node
echo "Terminate leader node($LEADER)."
docker-compose down "$LEADER"

# wait the next election occurs
echo "Wait 30s till the next election occurs..."
sleep 30

NEW_LEADER=$(detect_leader)
if [[ -z "$NEW_LEADER" ]]; then
  echo "New leader not found!"
  exit 1
fi
echo "Current leader(new): $NEW_LEADER"
sleep 3

echo "Insert a log..."
ENTITY_ID=1
NEW_LEADER_CID=$(cid "$NEW_LEADER")
docker exec "$NEW_LEADER_CID" curl -XPUT \
-H "Content-Type: application/json" "http://localhost:8080/op" \
-d"{\"type\": \"PUT\", \"id\": $ENTITY_ID, \"entity\": {\"id\": $ENTITY_ID, \"value\": \"Hello, World!\"}}"
sleep 3

# reconstruct nodes array
NEW_SERVICES=()
for svc in "${SERVICES[@]}"; do
  if [[ "$svc" != "$LEADER" ]]; then
    NEW_SERVICES+=("$svc")
  fi
done

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

# 3 = log + configuration(JOINT, FINAL)
verify_replication 3 "${NEW_SERVICES[@]}"
echo "Replication verified across the current nodes."

echo "Restart the pre-leader node($LEADER)!"
docker-compose up -d "$LEADER"
sleep 7


EXPECTED_LOG_LENGTH=$(docker exec "$NEW_LEADER_CID" curl -s "http://localhost:8080/logs" | jq 'length')
verify_replication "$EXPECTED_LOG_LENGTH" "${SERVICES[@]}"
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
echo 'State machine synced'

echo "Leader broke & failover test completed!"
bash ./tests/clean.sh