package com.gourmet.order;

import com.gourmet.order.OrderServiceGrpc.OrderServiceImplBase;
import io.grpc.stub.StreamObserver;

public class OrderServiceImpl extends OrderServiceImplBase {

    private final OrderRepository repository;

    public OrderServiceImpl(OrderRepository repository) {
        this.repository = repository;
    }

    @Override
    public void updateStatus(UpdateStatusRequest request,
                             StreamObserver<UpdateStatusResponse> responseObserver) {

        System.out.println("[OrderService] updateStatus called: "
                + request.getOrderId() + " -> " + request.getStatus());

        // Save to database
        repository.upsertOrder(request.getOrderId(), request.getStatus());

        // Send response back to caller
        responseObserver.onNext(
            UpdateStatusResponse.newBuilder()
                .setAcknowledged(true)
                .build()
        );
        responseObserver.onCompleted();
    }
}
