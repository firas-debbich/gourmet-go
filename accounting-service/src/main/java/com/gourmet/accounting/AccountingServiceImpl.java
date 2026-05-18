package com.gourmet.accounting;

import com.gourmet.accounting.AccountingServiceGrpc.AccountingServiceImplBase;
import io.grpc.stub.StreamObserver;

public class AccountingServiceImpl extends AccountingServiceImplBase {

    private final AccountingRepository repository;

    public AccountingServiceImpl(AccountingRepository repository) {
        this.repository = repository;
    }

    @Override
    public void authorizeCard(AuthorizeRequest request,
                              StreamObserver<AuthorizeResponse> responseObserver) {

        System.out.println("[AccountingService] authorizeCard called: "
                + request.getOrderId() + " amount=" + request.getAmount());

        // Business rule: amount < 100 = authorized
        boolean authorized = repository.authorizePayment(
                request.getOrderId(),
                request.getAmount()
        );

        responseObserver.onNext(
            AuthorizeResponse.newBuilder()
                .setAuthorized(authorized)
                .build()
        );
        responseObserver.onCompleted();
    }
}
