package com.example.proxy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Raw HTTP/1.1 client and a recording backend for the in-process proxy tests. */
final class TestHttp {
    private TestHttp() {}

    record Response(int status, Map<String, String> headers, String body) {
        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
    }

    record Received(String method, String path, Map<String, String> headers) {
        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
    }

    record Reply(int status, Map<String, String> headers, String body, long delayMillis) {
        static Reply ok() { return new Reply(200, Map.of(), "ok", 0); }
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Sends one request over a fresh connection; {@code headers} are written in order. */
    static Response send(int port, String method, String target, Map<String, String> headers, String body)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(30_000); // generous: a cold JVM on a loaded machine can be slow on its first request
            byte[] content = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder request = new StringBuilder(method + " " + target + " HTTP/1.1\r\n");
            boolean hasHost = false;
            for (Map.Entry<String, String> header : headers.entrySet()) {
                request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
                hasHost |= header.getKey().equalsIgnoreCase("Host");
            }
            if (!hasHost) request.append("Host: 127.0.0.1:").append(port).append("\r\n");
            request.append("Content-Length: ").append(content.length).append("\r\n");
            request.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.flush();
            return read(socket.getInputStream());
        }
    }

    private static Response read(InputStream in) throws IOException {
        String statusLine = line(in);
        int status = Integer.parseInt(statusLine.split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line = line(in); !line.isEmpty(); line = line(in)) {
            int colon = line.indexOf(':');
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        byte[] body = in.readNBytes(length);
        return new Response(status, headers, new String(body, StandardCharsets.UTF_8));
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bytes.write(c);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** A backend that records every request and answers with {@code reply}. */
    static final class Backend implements AutoCloseable {
        final HttpServer server;
        final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
        volatile Function<Received, Reply> reply = request -> Reply.ok();

        Backend() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                Map<String, String> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((name, values) ->
                        headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
                exchange.getRequestBody().readAllBytes();
                Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers);
                received.add(request);
                Reply answer = reply.apply(request);
                if (answer.delayMillis() > 0) {
                    try { Thread.sleep(answer.delayMillis()); } catch (InterruptedException ignored) { }
                }
                answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
                byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(answer.status(), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
        }

        int port() { return server.getAddress().getPort(); }

        Received next() throws InterruptedException {
            Received request = received.poll(30, TimeUnit.SECONDS);
            if (request == null) throw new AssertionError("backend received no request");
            return request;
        }

        List<Received> drain() {
            List<Received> all = new ArrayList<>();
            received.drainTo(all);
            return all;
        }

        @Override public void close() { server.stop(0); }
    }

    static Map<String, String> headers(String... nameValues) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i + 1 < nameValues.length; i += 2) headers.put(nameValues[i], nameValues[i + 1]);
        return headers;
    }
}
