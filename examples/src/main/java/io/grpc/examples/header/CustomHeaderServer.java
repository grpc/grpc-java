package io.grpc.examples.header;

import io.grpc.ServerImpl;
import io.grpc.ServerInterceptors;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.examples.helloworld.HelloResponse;
import io.grpc.stub.StreamObserver;
import io.grpc.transport.netty.NettyServerBuilder;

import java.util.logging.Logger;

/**
 * A simple server that like {@link io.grpc.examples.helloworld.HelloWorldServer}.
 * You can get and response any header in {@link io.grpc.examples.header.HeaderServerInterceptor}
 */
public class CustomHeaderServer {
  private static final Logger logger = Logger.getLogger(CustomHeaderServer.class.getName());

  /* The port on which the server should run */
  private static final int port = 50051;
  private ServerImpl server;

  private void start() throws Exception {
    server = NettyServerBuilder.forPort(port).addService(ServerInterceptors
            .intercept(GreeterGrpc.bindService(new GreeterImpl()), new HeaderServerInterceptor()))
            .build().start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        CustomHeaderServer.this.stop();
        System.err.println("*** server shut down");
      }
    });
  }

  private void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  /**
   * Main launches the server from the command line.
   */
  public static void main(String[] args) throws Exception {
    final CustomHeaderServer server = new CustomHeaderServer();
    server.start();
  }

  private class GreeterImpl implements GreeterGrpc.Greeter {

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloResponse> responseObserver) {
      HelloResponse reply = HelloResponse.newBuilder().setMessage("Hello " + req.getName()).build();
      responseObserver.onValue(reply);
      responseObserver.onCompleted();
    }
  }
}
