# syntax=docker/dockerfile:1
# Build context: repository root (needs admin-panel/ and libs/rpe-go).
FROM node:20-alpine AS web
WORKDIR /web
COPY admin-panel/web/package.json admin-panel/web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY admin-panel/web/ ./
RUN npm run build

FROM golang:1.24-alpine AS server
WORKDIR /src
COPY libs/rpe-go ./libs/rpe-go
COPY admin-panel/server ./admin-panel/server
WORKDIR /src/admin-panel/server
RUN --mount=type=cache,target=/root/go/pkg/mod CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/rpe-hub .

FROM gcr.io/distroless/static:nonroot
COPY --from=server /out/rpe-hub /rpe-hub
COPY --from=web /web/dist /console
ENV STATIC_DIR=/console
EXPOSE 8080
ENTRYPOINT ["/rpe-hub"]
