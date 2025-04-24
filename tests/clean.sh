#!/bin/bash
set -e

docker-compose down --remove-orphans
image_ids_raw=$(docker images --format "{{.Repository}}\t{{.ID}}" | grep raft-raft | awk '{print $2}' | sort -u)

if [ -n "$image_ids_raw" ]; then
  image_ids=$(echo "$image_ids_raw" | tr '\n' ' ')
  docker rmi -f $image_ids
else
  echo "There are no 'raft' named images."
fi