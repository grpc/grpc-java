package io.grpc.clientchannel.example;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.clientchannel.ClientChannelService;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.util.MutableHandlerRegistry;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TunnelClient {

    public static Metadata.Key<String> PREFIX_HEADER = Metadata.Key.of("zone", Metadata.ASCII_STRING_MARSHALLER);

    public static void main(String[] args) throws Exception {
        io.grpc.ManagedChannel networkChannel = ManagedChannelBuilder.forAddress("localhost", 50051).usePlaintext().build();

        networkChannel.getState(true);

        networkChannel.notifyWhenStateChanged(ConnectivityState.READY, () -> {
            Metadata headers = new Metadata();
            headers.put(PREFIX_HEADER, "Value: ");

            ClientChannelService.registerServer(
                    networkChannel,
                    headers,
                    b -> {
                        MutableHandlerRegistry registry = new MutableHandlerRegistry();
                        registry.addService(new VMDriverService());

                        b.fallbackHandlerRegistry(registry);
                    }
            );
        });

        Thread.currentThread().join();
    }

    private static class VMDriverService extends SimpleServiceGrpc.SimpleServiceImplBase {

        @Override
        public void serverStreamingRpc(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            AtomicInteger counter = new AtomicInteger(5);
            Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                    () -> {
                        int i = counter.getAndDecrement();
                        if (i > 0) {
                            responseObserver.onNext(SimpleResponse.newBuilder().setResponseMessage("Hello " + request.getRequestMessage() + " " + i).build());
                        } else {
                            responseObserver.onCompleted();
                            throw new RuntimeException("Completed");
                        }
                    },
                    0,
                    1,
                    TimeUnit.SECONDS
            );
        }
    }
}
