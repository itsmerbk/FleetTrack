package com.fleettrack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = 8084;
        FleetStore store = new FleetStore("fleettrack_ops.db");
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/", exchange -> {
            String html = Files.readString(Path.of("web/index.html"), StandardCharsets.UTF_8);
            sendResponse(exchange, 200, "text/html; charset=UTF-8", html);
        });

        server.createContext("/api/state", exchange -> {
            try {
                sendResponse(exchange, 200, "application/json", store.getDashboardStateJson());
            } catch (Exception e) {
                sendResponse(exchange, 500, "application/json", "{\"ok\":false,\"error\":\"" + JsonUtil.escape(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/orders", exchange -> {
            try {
                Map<String, String> body = parseBody(exchange);
                String cust = body.getOrDefault("customerName", "Express Customer");
                String hub = body.getOrDefault("pickupHub", "Central Hub");
                String drop = body.getOrDefault("dropZone", "Sector 9");
                double px = Double.parseDouble(body.getOrDefault("pickupX", "30"));
                double py = Double.parseDouble(body.getOrDefault("pickupY", "40"));
                double dx = Double.parseDouble(body.getOrDefault("dropX", "75"));
                double dy = Double.parseDouble(body.getOrDefault("dropY", "70"));
                int sla = Integer.parseInt(body.getOrDefault("slaMins", "20"));
                String res = store.createOrder(cust, hub, drop, px, py, dx, dy, sla, false);
                sendResponse(exchange, 200, "application/json", res);
            } catch (Exception e) {
                sendResponse(exchange, 400, "application/json", "{\"ok\":false,\"error\":\"" + JsonUtil.escape(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/dispatch", exchange -> {
            try {
                Map<String, String> body = parseBody(exchange);
                long orderId = Long.parseLong(body.getOrDefault("orderId", "0"));
                String res = store.dispatchNearestRider(orderId);
                sendResponse(exchange, res.contains("\"ok\":true") ? 200 : 409, "application/json", res);
            } catch (Exception e) {
                sendResponse(exchange, 400, "application/json", "{\"ok\":false,\"error\":\"" + JsonUtil.escape(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/tick", exchange -> {
            try {
                String res = store.stepSimulation(14.0);
                sendResponse(exchange, 200, "application/json", res);
            } catch (Exception e) {
                sendResponse(exchange, 500, "application/json", "{\"ok\":false,\"error\":\"" + JsonUtil.escape(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/complete", exchange -> {
            try {
                Map<String, String> body = parseBody(exchange);
                long orderId = Long.parseLong(body.getOrDefault("orderId", "0"));
                String res = store.completeDelivery(orderId);
                sendResponse(exchange, res.contains("\"ok\":true") ? 200 : 400, "application/json", res);
            } catch (Exception e) {
                sendResponse(exchange, 400, "application/json", "{\"ok\":false,\"error\":\"" + JsonUtil.escape(e.getMessage()) + "\"}");
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            try {
                store.close();
            } catch (Exception ignored) {
            }
        }));

        server.start();
        System.out.println("FleetTrack Live Delivery Dispatch & SLA Radar running at http://127.0.0.1:" + port);
    }

    private static Map<String, String> parseBody(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return JsonUtil.parseObject(raw);
    }

    private static void sendResponse(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
