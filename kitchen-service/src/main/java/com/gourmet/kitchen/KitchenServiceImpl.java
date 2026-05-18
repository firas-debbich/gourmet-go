package com.gourmet.kitchen;

import com.gourmet.kitchen.KitchenServiceGrpc.KitchenServiceImplBase;
import io.grpc.stub.StreamObserver;

public class KitchenServiceImpl extends KitchenServiceImplBase {

    private final KitchenRepository repository;

    public KitchenServiceImpl(KitchenRepository repository) {
        this.repository = repository;
    }

    // EXECUTION method — called during happy path
    @Override
    public void createTicket(TicketRequest request,
                             StreamObserver<TicketResponse> responseObserver) {

        System.out.println("[KitchenService] createTicket called for "
                + request.getOrderId());

        repository.createTicket(request.getOrderId());

        responseObserver.onNext(
            TicketResponse.newBuilder()
                .setSuccess(true)
                .build()
        );
        responseObserver.onCompleted();
    }

    // COMPENSATION method — called when payment fails
    // This is the "undo" for createTicket
    @Override
    public void rejectTicket(RejectRequest request,
                             StreamObserver<RejectResponse> responseObserver) {

        System.out.println("[KitchenService] rejectTicket called for "
                + request.getOrderId() + " (compensation)");

        repository.rejectTicket(request.getOrderId());

        responseObserver.onNext(
            RejectResponse.newBuilder()
                .setAcknowledged(true)
                .build()
        );
        responseObserver.onCompleted();
    }
}
