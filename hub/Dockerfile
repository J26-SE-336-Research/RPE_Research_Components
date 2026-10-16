# syntax=docker/dockerfile:1
# Build context: repository root (needs console/, hub/ and libs/rpe-go).
FROM node:20-alpine AS console
WORKDIR /console
COPY console/package.json console/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY console/ ./
RUN npm run build

FROM golang:1.24-alpine AS hub
WORKDIR /src
COPY libs/rpe-go ./libs/rpe-go
COPY hub ./hub
WORKDIR /src/hub
RUN --mount=type=cache,target=/root/go/pkg/mod CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/rpe-hub .

FROM gcr.io/distroless/static:nonroot
COPY --from=hub /out/rpe-hub /rpe-hub
COPY --from=console /console/dist /console
ENV STATIC_DIR=/console
EXPOSE 8080
ENTRYPOINT ["/rpe-hub"]
