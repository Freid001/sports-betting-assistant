package com.sportsbettingassistant.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * A tiny web server on localhost that stands in for The Odds API, football-data.org or GOAL API,
 * so tests never call the real services or spend credits. Answers are set per path; every request
 * is recorded.
 */
public final class FakeServer implements AutoCloseable {

    /** One recorded request. */
    public record Request(String path, String query, Map<String, List<String>> headers) {
        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    /** An answer: status, body and headers. */
    public record Answer(int status, String body, Map<String, String> headers) {
    }

    private final HttpServer server;
    private final Map<String, List<Answer>> answers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> served = new ConcurrentHashMap<>();
    public final List<Request> requests = new CopyOnWriteArrayList<>();

    public FakeServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(new Request(path, exchange.getRequestURI().getRawQuery(), Map.copyOf(exchange.getRequestHeaders())));
            List<Answer> list = answers.get(path);
            Answer a;
            if (list == null || list.isEmpty()) {
                a = new Answer(404, "{\"message\":\"no answer set for " + path + "\"}", Map.of());
            } else {
                // Several answers for one path are given in turn; the last one repeats.
                int i = served.computeIfAbsent(path, k -> new AtomicInteger()).getAndIncrement();
                a = list.get(Math.min(i, list.size() - 1));
            }
            byte[] bytes = a.body().getBytes(StandardCharsets.UTF_8);
            a.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(a.status(), bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    /** Base URL, e.g. http://127.0.0.1:54321 */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeServer answer(String path, int status, String body) {
        return answer(path, status, body, Map.of());
    }

    public FakeServer answer(String path, int status, String body, Map<String, String> headers) {
        answers.computeIfAbsent(path, k -> new CopyOnWriteArrayList<>()).add(new Answer(status, body, headers));
        return this;
    }

    public long count(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).count();
    }

    public Request last() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
