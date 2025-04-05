bd:
	./gradlew build
	docker-compose build

rund:
	docker-compose up -d

logf:
	docker-compose logs -f

clean:
	docker-compose down --remove-orphans