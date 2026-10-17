# syntax=docker/dockerfile:1
# Build context: the repository root (needs libs/rpe-java and netty-sidecar-proxy):
#   docker build -f netty-sidecar-proxy/docker/Dockerfile -t rpe/sidecar:dev .
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace
# Component 1 protocol library (X-RPE-Context codec)
COPY libs/rpe-java ./libs/rpe-java
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress -q -f libs/rpe-java/pom.xml -DskipTests install
COPY netty-sidecar-proxy/pom.xml ./netty-sidecar-proxy/pom.xml
COPY netty-sidecar-proxy/src ./netty-sidecar-proxy/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress -q -f netty-sidecar-proxy/pom.xml -DskipTests package

# The same image runs the rpe-init container (iptables / ip) and the proxy container.
FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends iproute2 iptables \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/netty-sidecar-proxy/target/netty-sidecar-proxy-1.0.0.jar app.jar

# 8080/9090: standalone TPROXY proxy and metrics; 15001/15006/15020/15090: RPE sidecar ports
EXPOSE 8080 9090 15001 15006 15020 15090

ENTRYPOINT ["java", "-jar", "app.jar"]
