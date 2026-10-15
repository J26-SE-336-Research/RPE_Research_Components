# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

# The same image runs the rpe-init container (iptables/ip) and the rpe-sidecar container.
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends iptables iproute2 \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/rpe-sidecar.jar /app/rpe-sidecar.jar
USER 1337:1337
EXPOSE 15001 15006 15020 15090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/rpe-sidecar.jar"]
