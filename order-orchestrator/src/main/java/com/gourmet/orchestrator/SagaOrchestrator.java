package com.gourmet.orchestrator;

import com.gourmet.accounting.AccountingServiceGrpc;
import com.gourmet.accounting.AuthorizeRequest;
import com.gourmet.kitchen.KitchenServiceGrpc;
import com.gourmet.kitchen.RejectRequest;
import com.gourmet.kitchen.TicketRequest;
import com.gourmet.order.OrderServiceGrpc;
import com.gourmet.order.UpdateStatusRequest;
import io.grpc.ManagedChannel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import java.net.InetSocketAddress;

public class SagaOrchestrator {

    private final OrderServiceGrpc.OrderServiceBlockingStub orderStub;
    private final KitchenServiceGrpc.KitchenServiceBlockingStub kitchenStub;
    private final AccountingServiceGrpc.AccountingServiceBlockingStub accountingStub;

    public SagaOrchestrator() {
        String orderHost      = getEnv("ORDER_SERVICE_HOST",      "localhost");
        String kitchenHost    = getEnv("KITCHEN_SERVICE_HOST",    "localhost");
        String accountingHost = getEnv("ACCOUNTING_SERVICE_HOST", "localhost");

        // NettyChannelBuilder with InetSocketAddress forces TCP — no Unix sockets
        ManagedChannel orderChannel = NettyChannelBuilder
                .forAddress(new InetSocketAddress(orderHost, 50051))
                .negotiationType(NegotiationType.PLAINTEXT)
                .build();

        ManagedChannel kitchenChannel = NettyChannelBuilder
                .forAddress(new InetSocketAddress(kitchenHost, 50052))
                .negotiationType(NegotiationType.PLAINTEXT)
                .build();

        ManagedChannel accountingChannel = NettyChannelBuilder
                .forAddress(new InetSocketAddress(accountingHost, 50053))
                .negotiationType(NegotiationType.PLAINTEXT)
                .build();

        this.orderStub      = OrderServiceGrpc.newBlockingStub(orderChannel);
        this.kitchenStub    = KitchenServiceGrpc.newBlockingStub(kitchenChannel);
        this.accountingStub = AccountingServiceGrpc.newBlockingStub(accountingChannel);
    }

    public SagaResult placeOrder(String orderId, double amount) {
        System.out.println("\n=== SAGA START: orderId=" + orderId + " amount=" + amount + " ===");

        // STEP 1: APPROVAL_PENDING
        System.out.println("[Saga] Step 1: Setting APPROVAL_PENDING");
        orderStub.updateStatus(UpdateStatusRequest.newBuilder()
                .setOrderId(orderId)
                .setStatus("APPROVAL_PENDING")
                .build());

        // STEP 2: Create kitchen ticket
        System.out.println("[Saga] Step 2: Creating kitchen ticket");
        boolean ticketCreated = false;
        try {
            var ticketResponse = kitchenStub.createTicket(
                    TicketRequest.newBuilder().setOrderId(orderId).build());
            ticketCreated = ticketResponse.getSuccess();
        } catch (Exception e) {
            System.out.println("[Saga] Kitchen ticket FAILED with exception: " + e.getMessage());
        }

        if (!ticketCreated) {
            System.out.println("[Saga] Kitchen ticket FAILED -> REJECTED");
            orderStub.updateStatus(UpdateStatusRequest.newBuilder()
                    .setOrderId(orderId).setStatus("REJECTED").build());
            return new SagaResult(orderId, "REJECTED", "Kitchen ticket creation failed");
        }

        // STEP 3: Authorize payment
        System.out.println("[Saga] Step 3: Authorizing payment");
        boolean authorized = false;
        try {
            var authResponse = accountingStub.authorizeCard(
                    AuthorizeRequest.newBuilder()
                            .setOrderId(orderId)
                            .setAmount(amount)
                            .build());
            authorized = authResponse.getAuthorized();
        } catch (Exception e) {
            System.out.println("[Saga] Payment authorization FAILED with exception: " + e.getMessage());
        }

        if (authorized) {
            // HAPPY PATH
            System.out.println("[Saga] Payment AUTHORIZED -> APPROVED");
            orderStub.updateStatus(UpdateStatusRequest.newBuilder()
                    .setOrderId(orderId).setStatus("APPROVED").build());
            return new SagaResult(orderId, "APPROVED", "Order placed successfully");

        } else {
            // COMPENSATION PATH
            System.out.println("[Saga] Payment REJECTED -> running compensation...");

            // Best-effort: reject the kitchen ticket; order must be finalized regardless
            System.out.println("[Saga] Compensation: RejectTicket");
            try {
                kitchenStub.rejectTicket(
                        RejectRequest.newBuilder().setOrderId(orderId).build());
            } catch (Exception e) {
                System.err.println("[Saga] WARNING: rejectTicket compensation failed (non-fatal): "
                        + e.getMessage());
            }

            // Always finalize the order — this must not be skipped
            System.out.println("[Saga] Compensation: Order -> REJECTED");
            orderStub.updateStatus(UpdateStatusRequest.newBuilder()
                    .setOrderId(orderId).setStatus("REJECTED").build());

            System.out.println("[Saga] Compensation complete.");
            return new SagaResult(orderId, "REJECTED", "Payment failed: amount >= 100");
        }
    }

    private String getEnv(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isEmpty()) ? val : defaultValue;
    }
}
