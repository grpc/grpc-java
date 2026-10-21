/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.examples.multiplex;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.examples.echo.EchoGrpc;
import io.grpc.examples.echo.EchoRequest;
import io.grpc.examples.echo.EchoResponse;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Service that echoes back whatever is sent to it.
 */
public class EchoService extends EchoGrpc.EchoImplBase {
  private static final Logger logger = Logger.getLogger(EchoService.class.getName());

  @Override
  public void unaryEcho(EchoRequest request,
      StreamObserver<EchoResponse> responseObserver) {
    logger.info("Received echo request: " + request.getMessage());
    EchoResponse response = EchoResponse.newBuilder().setMessage(request.getMessage()).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void serverStreamingEcho(EchoRequest request,
      StreamObserver<EchoResponse> responseObserver) {
    logger.info("Received server streaming echo request: " + request.getMessage());
    EchoResponse response = EchoResponse.newBuilder().setMessage(request.getMessage()).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public StreamObserver<EchoRequest> clientStreamingEcho(
      final StreamObserver<EchoResponse> responseObserver) {
    return new StreamObserver<EchoRequest>() {
      List<String> requestList = new ArrayList<>();

      @Override
      public void onNext(EchoRequest request) {
        logger.info("Received client streaming echo request: " + request.getMessage());
        requestList.add(request.getMessage());
      }

      @Override
      public void onError(Throwable t) {
        logger.log(Level.WARNING, "echo stream cancelled or had a problem and is no longer usable " + t.getMessage());
        responseObserver.onError(t);
      }

      @Override
      public void onCompleted() {
        logger.info("Client streaming complete");
        String reply = requestList.stream().collect(Collectors.joining(", "));
        EchoResponse response = EchoResponse.newBuilder().setMessage(reply).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    };
  }

  @Override
  public StreamObserver<EchoRequest> bidirectionalStreamingEcho(
      final StreamObserver<EchoResponse> responseObserver) {
    return new StreamObserver<EchoRequest>() {
      @Override
      public void onNext(EchoRequest request) {
        logger.info("Received bidirection streaming echo request: " + request.getMessage());
        EchoResponse response = EchoResponse.newBuilder().setMessage(request.getMessage()).build();
        responseObserver.onNext(response);
      }

      @Override
      public void onError(Throwable t) {
        logger.log(Level.WARNING,
            "echo stream cancelled or had a problem and is no longer usable " + t.getMessage());
        responseObserver.onError(t);
      }

      @Override
      public void onCompleted() {
        logger.info("Bidirectional stream completed from client side");
        responseObserver.onCompleted();
      }
    };
  }
}

