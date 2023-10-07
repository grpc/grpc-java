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

package io.grpc.examples.helloworld;
import java.util.ArrayList;
import java.util.List;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Server that manages startup/shutdown of a {@code Greeter} server.
 */
public class HelloWorldServer {
  private static final Logger logger = Logger.getLogger(HelloWorldServer.class.getName());

  private Server server;

  private void start() throws IOException {
    /* The port on which the server should run */
    int port = 50051;
    server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
        .addService(new GreeterImpl())
        .build()
        .start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        try {
          HelloWorldServer.this.stop();
        } catch (InterruptedException e) {
          e.printStackTrace(System.err);
        }
        System.err.println("*** server shut down");
      }
    });
  }

  private void stop() throws InterruptedException {
    if (server != null) {
      server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
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
    final HelloWorldServer server = new HelloWorldServer();
    server.start();
    server.blockUntilShutdown();
  }

  static class GreeterImpl extends GreeterGrpc.GreeterImplBase {

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
    String language = req.getLanguage();
    String greeting = "";
  
    switch (language) {
      case "ENGLISH":
       greeting = "Hello ";
       break;
       case "FRENCH":
        greeting = "Salut ";
         break;
       case "ARABIC":
        greeting = "Marhaba ";
        break;
       default:
        greeting = "Hello ";
  }
      HelloReply reply = HelloReply.newBuilder().setMessage(greeting + req.getName()).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }
   
@Override
public void sayHelloStream(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
    try {
        // Get the name and language from the request
        String name = request.getName();
        String language = request.getLanguage();

        // Greet the client multiple times based on their preferred language
        if (language.equals("ENGLISH")) {
            responseObserver.onNext(HelloReply.newBuilder().setMessage("Hello, " + name + "!").build());
            responseObserver.onNext(HelloReply.newBuilder().setMessage("How are you doing today?").build());
            responseObserver.onNext(HelloReply.newBuilder().setMessage("Have a nice day!").build());
        } else if (language.equals("FRENCH")) {
            responseObserver.onNext(HelloReply.newBuilder().setMessage("Bonjour, " + name + "!").build());
            responseObserver.onNext(HelloReply.newBuilder().setMessage("Comment allez-vous?").build());
            responseObserver.onNext(HelloReply.newBuilder().setMessage("Bonne journée!").build());
        } else if (language.equals("ARABIC")) {
            responseObserver.onNext(HelloReply.newBuilder().setMessage("marhaba " + name + "!").build());
            responseObserver.onNext(HelloReply.newBuilder().setMessage("labes?").build());
            responseObserver.onNext(HelloReply.newBuilder().setMessage("nharek zin").build());
        }

        // Indicate that the response stream has completed
        responseObserver.onCompleted();
    } catch (RuntimeException e) {
        // Handle any exceptions that occur during processing of the request
        responseObserver.onError(e);
        throw e;
    }
}



  }
}
