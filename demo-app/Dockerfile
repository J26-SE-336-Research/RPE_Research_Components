# Build context: repository root (needs libs/rpe-go).
FROM golang:1.24-alpine AS build
WORKDIR /src
COPY libs/rpe-go ./libs/rpe-go
COPY demo-app ./demo-app
WORKDIR /src/demo-app
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/demo-svc .

FROM gcr.io/distroless/static:nonroot
COPY --from=build /out/demo-svc /demo-svc
EXPOSE 8080
ENTRYPOINT ["/demo-svc"]
