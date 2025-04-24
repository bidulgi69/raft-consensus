FROM eclipse-temurin:17.0.5_8-jre-focal

# test tools
USER root
RUN apt-get update \
 && apt-get install -y iproute2 \
 && apt-get install -y iptables

COPY build/libs/*.jar app.jar
ENTRYPOINT ["sh", "-c", "java -jar /app.jar"]