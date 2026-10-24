# syntax=docker/dockerfile:1
# Build context: the repository root (needs libs/rpe-go, libs/rpe-java, conformance-kit and services/protocol-service):
#   docker build -f services/protocol-service/Dockerfile -t rpe/protocol-service:dev .
# One image, two containers in the pod: the Go service (rpe-go) and the rpe-java validator next to it.
FROM golang:1.24-alpine AS go
WORKDIR /src
COPY libs/rpe-go ./libs/rpe-go
COPY conformance-kit/go ./conformance-kit/go
COPY services/protocol-service/go.mod services/protocol-service/*.go ./services/protocol-service/
WORKDIR /src/services/protocol-service
RUN --mount=type=cache,target=/root/go/pkg/mod CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/protocol-service .

FROM maven:3.9.9-eclipse-temurin-21 AS java
WORKDIR /workspace
COPY libs/rpe-java ./libs/rpe-java
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress -q -f libs/rpe-java/pom.xml -DskipTests install
COPY services/protocol-service/java ./protocol-java
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress -q -f protocol-java/pom.xml package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=go /out/protocol-service /app/protocol-service
COPY --from=java /workspace/protocol-java/target/rpe-protocol-java.jar /app/java/rpe-protocol-java.jar
COPY --from=java /workspace/protocol-java/target/lib /app/java/lib
COPY conformance-kit/vectors /vectors
ENV VECTORS_DIR=/vectors
USER 65532:65532
EXPOSE 8095 8096
ENTRYPOINT ["/app/protocol-service"]
