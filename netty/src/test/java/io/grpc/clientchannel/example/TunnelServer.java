package io.grpc.clientchannel.example;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.clientchannel.ClientChannelService;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.testing.protobuf.SimpleServiceGrpc.SimpleServiceStub;

import static io.grpc.clientchannel.example.TunnelClient.PREFIX_HEADER;

public class TunnelServer {

    public static void main(String[] args) throws Exception {
        Server server = ServerBuilder
            .forPort(50051)
            .addService(
                new ClientChannelService() {
                    @Override
                    protected void onChannel(ManagedChannel channel, Metadata headers) {
                        String prefix = headers.get(PREFIX_HEADER);
                        SimpleServiceStub stub = SimpleServiceGrpc.newStub(channel);

                        stub.serverStreamingRpc(
                                SimpleRequest.newBuilder().setRequestMessage("foo").build(),
                                new StreamObserver<SimpleResponse>() {
                                    @Override
                                    public void onNext(SimpleResponse value) {
                                        System.out.println(prefix + value.getResponseMessage());
                                    }

                                    @Override
                                    public void onError(Throwable t) {
                                        t.printStackTrace();
                                    }

                                    @Override
                                    public void onCompleted() {
                                        System.out.println("Completed");
                                    }
                                }
                        );
                    }
                }
            )
            .build()
            .start();

        server.awaitTermination();
    }
}
