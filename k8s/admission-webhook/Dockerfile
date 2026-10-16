FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
COPY cmd ./cmd
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/rpe-admission-webhook . \
 && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/rpe-admission-certgen ./cmd/certgen

FROM gcr.io/distroless/static:nonroot
COPY --from=build /out/rpe-admission-webhook /rpe-admission-webhook
COPY --from=build /out/rpe-admission-certgen /rpe-admission-certgen
EXPOSE 8443
ENTRYPOINT ["/rpe-admission-webhook"]
