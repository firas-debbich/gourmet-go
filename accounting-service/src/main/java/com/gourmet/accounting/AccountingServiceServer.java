package com.gourmet.accounting;

import io.grpc.Server;
import io.grpc.ServerBuilder;

public class AccountingServiceServer {

    private static final int PORT = 50053;

    public static void main(String[] args) throws Exception {
        System.out.println("[AccountingService] Starting on port " + PORT);

        AccountingRepository repository = new AccountingRepository();
        repository.initTable();

        Server server = ServerBuilder
                .forPort(PORT)
                .addService(new AccountingServiceImpl(repository))
                .build();

        server.start();
        System.out.println("[AccountingService] Ready on port " + PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[AccountingService] Shutting down...");
            server.shutdown();
        }));

        server.awaitTermination();
    }
}
