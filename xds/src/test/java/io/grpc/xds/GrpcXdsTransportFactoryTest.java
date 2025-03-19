/*
 * Copyright 2024 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.util.concurrent.SettableFuture;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.BindableService;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.XdsTransportFactory;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GrpcXdsTransportFactoryTest {

  private Server server;

  @Before
  public void setup() throws Exception {
    server = Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
        .addService(echoAdsService())
        .build()
        .start();
  }

  @After
  public void tearDown() {
    server.shutdown();
  }

  private BindableService echoAdsService() {
    return new AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        StreamObserver<DiscoveryRequest> requestObserver = new StreamObserver<DiscoveryRequest>() {
          @Override
          public void onNext(DiscoveryRequest value) {
            responseObserver.onNext(DiscoveryResponse.newBuilder()
                .setVersionInfo(value.getVersionInfo())
                .setNonce(value.getResponseNonce())
                .build());
          }

          @Override
          public void onError(Throwable t) {
            responseObserver.onError(t);
          }

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };

        return requestObserver;
      }
    };
  }

  @Test
  public void callApis() throws Exception {
    XdsTransportFactory.XdsTransport xdsTransport =
        new GrpcXdsTransportFactory(null)
            .create(
                Bootstrapper.ServerInfo.create(
                    "localhost:" + server.getPort(), InsecureChannelCredentials.create()));
    MethodDescriptor<DiscoveryRequest, DiscoveryResponse> methodDescriptor =
        AggregatedDiscoveryServiceGrpc.getStreamAggregatedResourcesMethod();
    XdsTransportFactory.StreamingCall<DiscoveryRequest, DiscoveryResponse> streamingCall =
        xdsTransport.createStreamingCall(methodDescriptor.getFullMethodName(),
        methodDescriptor.getRequestMarshaller(), methodDescriptor.getResponseMarshaller());
    FakeEventHandler fakeEventHandler = new FakeEventHandler();
    streamingCall.start(fakeEventHandler);
    streamingCall.sendMessage(
        DiscoveryRequest.newBuilder().setVersionInfo("v1").setResponseNonce("2024").build());
    DiscoveryResponse response = fakeEventHandler.respQ.poll(5000, TimeUnit.MILLISECONDS);
    assertThat(response.getVersionInfo()).isEqualTo("v1");
    assertThat(response.getNonce()).isEqualTo("2024");
    assertThat(fakeEventHandler.ready.get(5000, TimeUnit.MILLISECONDS)).isTrue();
    Exception expectedException = new IllegalStateException("Test cancel stream.");
    streamingCall.sendError(expectedException);
    Status realStatus = fakeEventHandler.endFuture.get(5000, TimeUnit.MILLISECONDS);
    assertThat(realStatus.getDescription()).isEqualTo("Cancelled by XdsClientImpl");
    assertThat(realStatus.getCode()).isEqualTo(Status.CANCELLED.getCode());
    assertThat(realStatus.getCause()).isEqualTo(expectedException);
    xdsTransport.shutdown();
  }

  private static class FakeEventHandler implements
      XdsTransportFactory.EventHandler<DiscoveryResponse> {
    private final BlockingQueue<DiscoveryResponse> respQ = new LinkedBlockingQueue<>();
    private SettableFuture<Status> endFuture = SettableFuture.create();
    private SettableFuture<Boolean> ready = SettableFuture.create();

    @Override
    public void onReady() {
      ready.set(true);
    }

    @Override
    public void onRecvMessage(DiscoveryResponse message) {
      respQ.offer(message);
    }

    @Override
    public void onStatusReceived(Status status) {
      endFuture.set(status);
    }
  }
}

