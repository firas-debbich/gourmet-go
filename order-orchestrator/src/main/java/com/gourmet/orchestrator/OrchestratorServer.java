package com.gourmet.orchestrator;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP server for the Orchestrator.
 * Uses Java's built-in HttpServer — zero dependencies.
 *
 * Exposes one endpoint:
 *   POST /saga/order  { "orderId": "...", "amount": 45.0 }
 */
public class OrchestratorServer {

    private static final int PORT = 8084;
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        System.out.println("[Orchestrator] Starting HTTP server on port " + PORT);

        // Create the Saga orchestrator (opens gRPC channels)
        SagaOrchestrator orchestrator = new SagaOrchestrator();

        // Use Java's built-in HTTP server — no Spring, no Javalin
        HttpServer server = HttpServer.create(
                new InetSocketAddress(PORT), 0);

        // Register the /saga/order endpoint
        server.createContext("/saga/order", exchange -> {
            // Add CORS headers so the React UI can call us
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods",
                    "POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers",
                    "Content-Type");

            // Handle preflight OPTIONS request from browser
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            try {
                // Read the request body
                String body = readBody(exchange);
                System.out.println("[Orchestrator] Received: " + body);

                // Parse JSON: { "orderId": "ORDER-001", "amount": 45.0 }
                Map<?, ?> request = GSON.fromJson(body, Map.class);
                String orderId = (String) request.get("orderId");
                double amount  = ((Number) request.get("amount")).doubleValue();

                // Run the Saga
                SagaResult result = orchestrator.placeOrder(orderId, amount);

                // Return result as JSON
                String json = GSON.toJson(result);
                sendResponse(exchange, 200, json);

            } catch (Exception e) {
                System.err.println("[Orchestrator] Error: " + e.getMessage());
                sendResponse(exchange, 500,
                        "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // Health check endpoint
        server.createContext("/health", exchange -> {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            sendResponse(exchange, 200, "{\"status\":\"UP\"}");
        });

        server.setExecutor(null);
        server.start();
        System.out.println("[Orchestrator] Ready on port " + PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Orchestrator] Shutting down...");
            server.stop(0);
        }));
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendResponse(HttpExchange exchange,
                                     int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
