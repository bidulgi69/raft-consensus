FROM eclipse-temurin:17.0.5_8-jre-focal
COPY build/libs/*.jar app.jar
ENTRYPOINT ["sh", "-c", "java -jar /app.jar"]