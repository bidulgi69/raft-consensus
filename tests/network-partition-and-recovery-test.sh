#!/bin/bash
SERVICES=(raft-node1 raft-node2 raft-node3)
cid() { docker-compose -f docker-compose.partition.yml ps -q "$1"; }
get_ip() {
  cid="$1"
  docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$cid"
}
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

./gradlew build
docker-compose -f docker-compose.partition.yml build

echo 'Setup cluster...'
docker-compose -f docker-compose.partition.yml up -d "${SERVICES[@]}"
sleep 15

LEADER=$(detect_leader)
if [[ -z "$LEADER" ]]; then
  echo "Leader not found!"
  exit 1
fi
echo "Current leader: $LEADER"

# partitioning networks
PARTITION_DURATION=30
RECOVERY_WAIT=15

echo "Partitioning leader ($LEADER) from followers..."
for svc in "${SERVICES[@]}"; do
  if [[ "$svc" != "$LEADER" ]]; then
    CID_L=$(cid "$LEADER")
    CID_F=$(cid "$svc")
    IP_L=$(get_ip "$CID_L")
    IP_F=$(get_ip "$CID_F")
    # unlinks leader -> follower
    docker exec -u root "$CID_L" iptables -A OUTPUT -d "$IP_F" -j DROP
    # unlinks follower -> leader
    docker exec -u root "$CID_F" iptables -A OUTPUT -d "$IP_L" -j DROP
  fi
done
echo "Network partition in effect for $PARTITION_DURATION seconds..."
sleep "$PARTITION_DURATION"

# Verify a new leader has been elected for next gen
NEW_LEADER=$(for svc in "${SERVICES[@]}"; do
  if [[ "$svc" != "$LEADER" ]]; then
    CID=$(cid "$svc")
    role=$(docker exec "$CID" curl -s "http://localhost:8080/type")
    if [[ "$role" = "\"LEADER\"" ]]; then
      echo "$svc"
      break
    fi
  fi
done)

if [[ -z "$NEW_LEADER" ]]; then
  echo "Failover did not occur!"
  exit 1
else
  echo "New leader after partition: $NEW_LEADER"
fi

# Recovery networks
echo "Recovering network links..."
for svc in "${SERVICES[@]}"; do
  if [[ "$svc" != "$LEADER" ]]; then
    CID_L=$(cid "$LEADER")
    CID_F=$(cid "$svc")
    IP_L=$(get_ip "$CID_L")
    IP_F=$(get_ip "$CID_F")
    docker exec -u root "$CID_L" iptables -D OUTPUT -d "$IP_F" -j DROP
    docker exec -u root "$CID_F" iptables -D OUTPUT -d "$IP_L"   -j DROP
  fi
done
echo "Waiting $RECOVERY_WAIT seconds for cluster to stabilize..."
sleep "$RECOVERY_WAIT"


# After recovery, old-leader node would follow the new leader
# Because it has lower term
CURRENT_LEADER=$(detect_leader)
if [[ "$NEW_LEADER" != "$CURRENT_LEADER" ]]; then
  echo "Failed to verify new leader."
  exit 1;
fi

echo "Network partition & recovery test completed."
bash ./tests/clean.sh