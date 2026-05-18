package com.gourmet.order;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;

/**
 * Simple HTTP server inside Order Service.
 * Exposes GET /orders and GET /orders/{id}
 * So the API Gateway can query order status.
 */
public class OrderHttpServer {

    private static final int HTTP_PORT = 8081;
    private final OrderRepository repository;

    public OrderHttpServer(OrderRepository repository) {
        this.repository = repository;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(HTTP_PORT), 0);

        // GET /orders — return all orders as JSON
        server.createContext("/orders", exchange -> {
            exchange.getResponseHeaders().add(
                    "Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add(
                    "Content-Type", "application/json");

            String path = exchange.getRequestURI().getPath();
            String response;

            // Check if it's /orders/{id} or /orders
            if (path.matches("/orders/[^/]+")) {
                String orderId = path.substring("/orders/".length());
                response = repository.findOrderAsJson(orderId);
            } else {
                response = repository.findAllAsJson();
            }

            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.setExecutor(null);
        server.start();
        System.out.println("[OrderService] HTTP server on port " + HTTP_PORT);
    }
}
