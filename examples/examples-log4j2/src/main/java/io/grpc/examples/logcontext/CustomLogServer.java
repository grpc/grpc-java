/*
 * Copyright 2015 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.examples.logcontext;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.message.ReusableMessageFactory;
import org.apache.logging.log4j.simple.SimpleLogger;
import org.apache.logging.log4j.util.PropertiesUtil;

/**
 * A simple server that like {@link io.grpc.examples.helloworld.HelloWorldServer}.
 * It uses {@link HeaderServerInterceptor} to set the correct logging context for the stub.
 */
public class CustomLogServer {

  private static final org.apache.logging.log4j.Logger logger = new SimpleLogger(
      CustomLogServer.class.getName(),
      Level.INFO, /* showLogName= */ false,
      /*showShortLogName=*/ false,
      /*showDateTime=*/ true,
      /*showContextMap=*/ true,
      "yyyy/MM/dd HH:mm:ss:SSS zzz",
      new ReusableMessageFactory(),
      PropertiesUtil.getProperties(),
      System.err);

  /* The port on which the server should run */
  private static final int PORT = 50051;
  private Server server;

  private void start() throws IOException {
    server = ServerBuilder.forPort(PORT)
        .addService(ServerInterceptors.intercept(new GreeterImpl(), new HeaderServerInterceptor()))
        .build()
        .start();
    logger.info("Server started, listening on " + PORT);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        CustomLogServer.this.stop();
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
   * Await termination on the main thread since the grpc library uses daemon threads.
   */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  /**
   * Main launches the server from the command line.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    final CustomLogServer server = new CustomLogServer();
    server.start();
    server.blockUntilShutdown();
  }

  private static class GreeterImpl extends GreeterGrpc.GreeterImplBase {

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      logger.info("Got a request");
      // outputs something like:
      // 2019/06/05 15:22:12:686 PDT INFO Got a request
      // {requestId=3e6c256d-6e87-411e-8bf3-fbf81e7ce0e6, clientName=my.domain.name}
      HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }
  }
}
