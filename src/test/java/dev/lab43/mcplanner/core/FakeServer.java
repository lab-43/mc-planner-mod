package dev.lab43.mcplanner.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A scripted local HTTP server for client tests. */
final class FakeServer implements AutoCloseable {
    record Req(String method, String path, Map<String, String> headers, String body, long at) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    record Reply(int status, String body, Map<String, String> headers) {
        static Reply json(int status, String body) {
            return new Reply(status, body, new LinkedHashMap<>(Map.of("Content-Type", "application/json")));
        }

        static Reply empty(int status) {
            return new Reply(status, null, new LinkedHashMap<>());
        }

        Reply header(String k, String v) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put(k, v);
            return new Reply(status, body, h);
        }
    }

    interface Route {
        Reply handle(Req r) throws Exception;
    }

    final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fake-server");
        t.setDaemon(true);
        return t;
    });

    FakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(pool);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void on(String method, String path, Route r) {
        routes.put(method + " " + path, r);
    }

    List<Req> requests(String method, String path) {
        List<Req> out = new ArrayList<>();
        synchronized (requests) {
            for (Req r : requests) if (r.method.equals(method) && r.path.equals(path)) out.add(r);
        }
        return out;
    }

    int count(String method, String path) {
        return requests(method, path).size();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
            String path = ex.getRequestURI().getRawPath();
            Req req = new Req(ex.getRequestMethod(), path, headers, body, System.currentTimeMillis());
            requests.add(req);
            Route route = routes.get(req.method + " " + path);
            Reply reply = route != null ? route.handle(req) : Reply.json(404, "{\"error\":\"no route " + req.method + " " + path + "\"}");
            reply.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            byte[] bytes = reply.body() != null ? reply.body().getBytes(StandardCharsets.UTF_8) : new byte[0];
            boolean noBody = reply.status() == 304 || reply.status() == 204 || bytes.length == 0;
            ex.sendResponseHeaders(reply.status(), noBody ? -1 : bytes.length);
            if (!noBody) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
        } catch (Exception e) {
            byte[] b = ("{\"error\":\"test server: " + e + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        } finally {
            ex.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }
}
