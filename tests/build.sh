#!/bin/bash
set -e

./gradlew build
docker-compose build