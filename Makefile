bd:
	./gradlew clean
	./gradlew build
	docker-compose build

init-cluster:
	docker-compose up -d raft-node1 raft-node2 raft-node3

logf:
	docker-compose logs -f

clean:
	docker-compose down --remove-orphans