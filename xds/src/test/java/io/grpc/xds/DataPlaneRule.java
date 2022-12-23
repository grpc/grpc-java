/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.xds;

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

/**
 * This rule creates a new server instance in the "data plane" that is configured by a "control
 * plane" xDS server.
 */
public class DataPlaneRule extends TestWatcher {
  private static final Logger logger = Logger.getLogger(DataPlaneRule.class.getName());

  private static final String SERVER_HOST_NAME = "test-server";
  private static final String SCHEME = "test-xds";

  private final ControlPlaneRule controlPlane;
  private Server server;
  private HashSet<ManagedChannel> channels = new HashSet<>();

  /**
   * Creates a new {@link DataPlaneRule} that is connected to the given {@link ControlPlaneRule}.
   */
  public DataPlaneRule(ControlPlaneRule controlPlane) {
    this.controlPlane = controlPlane;
  }

  /**
   * Returns the server instance.
   */
  public Server getServer() {
    return server;
  }

  /**
   * Returns a newly created {@link ManagedChannel} to the server.
   */
  public ManagedChannel getManagedChannel() {
    ManagedChannel channel = Grpc.newChannelBuilder(SCHEME + ":///" + SERVER_HOST_NAME,
        InsecureChannelCredentials.create()).build();
    channels.add(channel);
    return channel;
  }

  @Override
  protected void starting(Description description) {
    // Let the control plane know about our new server.
    controlPlane.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener(SERVER_HOST_NAME)
    );

    // Start up the server.
    try {
      startServer(controlPlane.defaultBootstrapOverride());
    } catch (Exception e) {
      throw new AssertionError("unable to start the data plane server", e);
    }

    // Provide the rest of the configuration to the control plane.
    controlPlane.setRdsConfig(ControlPlaneRule.buildRouteConfiguration(SERVER_HOST_NAME));
    controlPlane.setCdsConfig(ControlPlaneRule.buildCluster());
    InetSocketAddress edsInetSocketAddress = (InetSocketAddress) server.getListenSockets().get(0);
    controlPlane.setEdsConfig(
        ControlPlaneRule.buildClusterLoadAssignment(edsInetSocketAddress.getHostName(),
            edsInetSocketAddress.getPort()));
  }

  @Override
  protected void finished(Description description) {
    if (server != null) {
      // Shut down any lingering open channels to the server.
      for (ManagedChannel channel : channels) {
        if (!channel.isShutdown()) {
          channel.shutdownNow();
        }
      }

      // Shut down the server itself.
      server.shutdownNow();
      try {
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
          logger.log(Level.SEVERE, "Timed out waiting for server shutdown");
        }
      } catch (InterruptedException e) {
        throw new AssertionError("unable to shut down data plane server", e);
      }
    }
  }

  private void startServer(Map<String, ?> bootstrapOverride) throws Exception {
    ServerInterceptor metadataInterceptor = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
          Metadata requestHeaders, ServerCallHandler<ReqT, RespT> next) {
        logger.fine("Received following metadata: " + requestHeaders);

        // Make a copy of the headers so that it can be read in a thread-safe manner when copying
        // it to the response headers.
        Metadata headersToReturn = new Metadata();
        headersToReturn.merge(requestHeaders);

        return next.startCall(new SimpleForwardingServerCall<ReqT, RespT>(call) {
          @Override
          public void sendHeaders(Metadata responseHeaders) {
            responseHeaders.merge(headersToReturn);
            super.sendHeaders(responseHeaders);
          }

          @Override
          public void close(Status status, Metadata trailers) {
            super.close(status, trailers);
          }
        }, requestHeaders);
      }
    };

    SimpleServiceGrpc.SimpleServiceImplBase simpleServiceImpl =
        new SimpleServiceGrpc.SimpleServiceImplBase() {
          @Override
          public void unaryRpc(
              SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            SimpleResponse response =
                SimpleResponse.newBuilder().setResponseMessage("Hi, xDS!").build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
          }
        };

    XdsServerBuilder serverBuilder = XdsServerBuilder.forPort(
            0, InsecureServerCredentials.create())
        .addService(simpleServiceImpl)
        .intercept(metadataInterceptor)
        .overrideBootstrapForTest(bootstrapOverride);
    server = serverBuilder.build().start();
    logger.log(Level.FINE, "data plane server started");
  }
}
