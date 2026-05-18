package com.gourmet.kitchen;

import io.grpc.Server;
import io.grpc.ServerBuilder;

public class KitchenServiceServer {

    private static final int PORT = 50052;

    public static void main(String[] args) throws Exception {
        System.out.println("[KitchenService] Starting on port " + PORT);

        KitchenRepository repository = new KitchenRepository();
        repository.initTable();

        Server server = ServerBuilder
                .forPort(PORT)
                .addService(new KitchenServiceImpl(repository))
                .build();

        server.start();
        System.out.println("[KitchenService] Ready on port " + PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[KitchenService] Shutting down...");
            server.shutdown();
        }));

        server.awaitTermination();
    }
}
