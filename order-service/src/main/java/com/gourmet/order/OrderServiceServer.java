package com.gourmet.order;

import io.grpc.Server;
import io.grpc.ServerBuilder;

public class OrderServiceServer {

    private static final int GRPC_PORT = 50051;

    public static void main(String[] args) throws Exception {
        System.out.println("[OrderService] Starting...");

        OrderRepository repository = new OrderRepository();
        repository.initTable();

        // Start gRPC server
        Server grpcServer = ServerBuilder
                .forPort(GRPC_PORT)
                .addService(new OrderServiceImpl(repository))
                .build();
        grpcServer.start();
        System.out.println("[OrderService] gRPC ready on port " + GRPC_PORT);

        // Start HTTP server for status queries
        OrderHttpServer httpServer = new OrderHttpServer(repository);
        httpServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[OrderService] Shutting down...");
            grpcServer.shutdown();
        }));

        grpcServer.awaitTermination();
    }
}
