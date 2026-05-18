package com.gourmet.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * API Gateway Controller.
 *
 * All requests from the React UI come here first.
 * This class forwards them to the right backend service.
 *
 * Why a gateway?
 * - React only knows ONE address (the gateway)
 * - The gateway knows where each service lives
 * - Services can move/change ports without affecting the UI
 */
@RestController
@CrossOrigin(origins = "*")
public class GatewayController {

    // Read service URLs from application.yml
    @Value("${services.orchestrator-url}")
    private String orchestratorUrl;

    @Value("${services.order-service-url}")
    private String orderServiceUrl;

    // RestTemplate = Spring's HTTP client
    // We use it to forward requests to other services
    private final RestTemplate restTemplate = buildRestTemplate();

private RestTemplate buildRestTemplate() {
    org.springframework.http.client.SimpleClientHttpRequestFactory factory =
        new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5000);
    factory.setReadTimeout(10000);
    return new RestTemplate(factory);
}

    /**
     * POST /api/orders
     * Body: { "orderId": "ORDER-001", "amount": 45.0 }
     *
     * Forwards to Orchestrator which runs the Saga.
     */
    @PostMapping("/api/orders")
public ResponseEntity<?> createOrder(@RequestBody Map<String, Object> body) {
    System.out.println("[Gateway] POST /api/orders -> " + orchestratorUrl);
    try {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        return restTemplate.postForEntity(
                orchestratorUrl + "/saga/order",
                entity,
                Object.class
        );
    } catch (Exception e) {
        System.err.println("[Gateway] Error: " + e.getMessage());
        return ResponseEntity.status(500)
                .body(Map.of("error", e.getMessage()));
    }
}

    /**
     * GET /api/orders/{orderId}
     * Returns the current status of one order.
     *
     * Forwards to Order Service directly.
     */
    @GetMapping("/api/orders/{orderId}")
    public ResponseEntity<?> getOrder(@PathVariable String orderId) {
        System.out.println("[Gateway] GET /api/orders/" + orderId);
        return restTemplate.getForEntity(
                orderServiceUrl + "/orders/" + orderId,
                Object.class
        );
    }

    /**
     * GET /api/orders
     * Returns all orders.
     */
    @GetMapping("/api/orders")
    public ResponseEntity<?> getAllOrders() {
        System.out.println("[Gateway] GET /api/orders");
        return restTemplate.getForEntity(
                orderServiceUrl + "/orders",
                Object.class
        );
    }

    /**
     * GET /api/health
     * Simple health check.
     */
    @GetMapping("/api/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "api-gateway"));
    }
}
