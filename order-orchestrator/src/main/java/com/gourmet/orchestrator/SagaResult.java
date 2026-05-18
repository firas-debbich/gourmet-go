package com.gourmet.orchestrator;

/**
 * Simple result object returned after a Saga execution.
 * Gson will convert this to JSON automatically.
 */
public class SagaResult {
    public final String orderId;
    public final String status;
    public final String message;

    public SagaResult(String orderId, String status, String message) {
        this.orderId = orderId;
        this.status  = status;
        this.message = message;
    }
}
