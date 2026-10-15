FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod main.go ./
COPY cmd ./cmd
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/rrpe-admission-webhook . \
    && CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/rrpe-admission-certgen ./cmd/certgen

FROM scratch
COPY --from=build /out/rrpe-admission-webhook /rrpe-admission-webhook
COPY --from=build /out/rrpe-admission-certgen /rrpe-admission-certgen
USER 10001:10001
EXPOSE 8443
ENTRYPOINT ["/rrpe-admission-webhook"]
