# syntax=docker/dockerfile:1
FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/root/go/pkg/mod go mod download
COPY *.go ./
RUN --mount=type=cache,target=/root/go/pkg/mod CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/rpe-operator .

FROM gcr.io/distroless/static:nonroot
COPY --from=build /out/rpe-operator /rpe-operator
EXPOSE 8080
ENTRYPOINT ["/rpe-operator"]
