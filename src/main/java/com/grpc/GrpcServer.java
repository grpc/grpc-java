package com.grpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;

public class GrpcServer {

    public static void main(String args[]) throws InterruptedException, IOException {
        Server server = ServerBuilder.forPort(8083).addService(new GreeterService()).build();
        server.start();
        System.out.println("Server started at " + server.getPort());
        server.awaitTermination();  //Wait and Terminate
    }
}

