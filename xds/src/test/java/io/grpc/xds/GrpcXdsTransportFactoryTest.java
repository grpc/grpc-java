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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.SettableFuture;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelConfigurator;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.NoopClientCall;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.XdsTransportFactory;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
        new GrpcXdsTransportFactory(null, null)
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

  @Test
  public void verifyConfigApplied_interceptor() {
    final boolean[] interceptorCalled = new boolean[1];
    final ClientInterceptor interceptor = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method,
          CallOptions callOptions,
          Channel next) {
        interceptorCalled[0] = true;
        return new NoopClientCall<>();
      }
    };

    // Create Configurer that adds the interceptor
    ChannelConfigurator configurer = new ChannelConfigurator() {
      @Override
      public void configureChannelBuilder(ManagedChannelBuilder<?> builder) {
        builder.intercept(interceptor);
      }
    };

    // Create Factory
    GrpcXdsTransportFactory factory = new GrpcXdsTransportFactory(
        null,
        configurer);

    // Create Transport
    XdsTransportFactory.XdsTransport transport = factory.create(
        Bootstrapper.ServerInfo.create("localhost:8080", InsecureChannelCredentials.create()));

    // Create a Call to trigger interceptors
    MethodDescriptor<Void, Void> method = MethodDescriptor.<Void, Void>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("service/method")
        .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
        .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
        .build();

    transport.createStreamingCall(method.getFullMethodName(), method.getRequestMarshaller(),
        method.getResponseMarshaller());

    // Verify interceptor was invoked
    assertThat(interceptorCalled[0]).isTrue();

    transport.shutdown();
  }

  @Test
  public void useChannelConfigurator() {
    final boolean[] called = new boolean[1];
    ChannelConfigurator configurer = new ChannelConfigurator() {
      @Override
      public void configureChannelBuilder(ManagedChannelBuilder<?> builder) {
        called[0] = true;
      }
    };

    // Create Factory
    GrpcXdsTransportFactory factory = new GrpcXdsTransportFactory(
        null, // CallCredentials
        configurer);

    // Create Transport (triggers channel creation)
    XdsTransportFactory.XdsTransport transport = factory.create(
        Bootstrapper.ServerInfo.create("localhost:8080", InsecureChannelCredentials.create()));

    // Verify Configurer was accessed and applied
    assertThat(called[0]).isTrue();

    transport.shutdown();
  }

  @Test
  public void useChannelConfigurator_setsChildChannelConfigurator() {
    final AtomicReference<NameResolver.Args> capturedArgs = new AtomicReference<>();
    NameResolverProvider testProvider = new NameResolverProvider() {
      @Override
      public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        capturedArgs.set(args);
        NameResolver resolver = mock(NameResolver.class);
        when(resolver.getServiceAuthority()).thenReturn("localhost:8080");
        return resolver;
      }

      @Override
      public String getDefaultScheme() {
        return "test-xds-transport";
      }

      @Override
      protected boolean isAvailable() {
        return true;
      }

      @Override
      protected int priority() {
        return 10;
      }
    };
    NameResolverRegistry.getDefaultRegistry().register(testProvider);
    try {
      ChannelConfigurator configurer = builder -> { };
      GrpcXdsTransportFactory factory = new GrpcXdsTransportFactory(null, configurer);
      XdsTransportFactory.XdsTransport transport = factory.create(
          Bootstrapper.ServerInfo.create(
              "test-xds-transport://localhost:8080", InsecureChannelCredentials.create()));
      assertNotNull(capturedArgs.get());
      assertSame(configurer, capturedArgs.get().getChildChannelConfigurator());
      transport.shutdown();
    } finally {
      NameResolverRegistry.getDefaultRegistry().deregister(testProvider);
    }
  }

  @Test
  public void useChannelConfigurator_throwsException_propagates() {
    final RuntimeException testException = new RuntimeException("test exception");
    ChannelConfigurator configurer = new ChannelConfigurator() {
      @Override
      public void configureChannelBuilder(ManagedChannelBuilder<?> builder) {
        throw testException;
      }
    };

    GrpcXdsTransportFactory factory = new GrpcXdsTransportFactory(null, configurer);

    try {
      factory.create(
          Bootstrapper.ServerInfo.create("localhost:8080", InsecureChannelCredentials.create()));
      org.junit.Assert.fail("Expected RuntimeException");
    } catch (RuntimeException e) {
      assertThat(e).isSameInstanceAs(testException);
    }
  }

  @Test
  public void verifyConfigApplied_maxInboundMessageSize() {
    // Create a mock Builder
    ManagedChannelBuilder<?> mockBuilder = mock(ManagedChannelBuilder.class);

    // Create Configurer that modifies message size
    ChannelConfigurator configurer = new ChannelConfigurator() {
      @Override
      public void configureChannelBuilder(ManagedChannelBuilder<?> builder) {
        builder.maxInboundMessageSize(1024);
      }
    };

    // Apply configurer to builder
    configurer.configureChannelBuilder(mockBuilder);

    // Verify builder was modified
    verify(mockBuilder).maxInboundMessageSize(1024);
  }

  @Test
  public void verifyConfigApplied_interceptors() {
    ClientInterceptor interceptor1 = mock(ClientInterceptor.class);
    ClientInterceptor interceptor2 = mock(ClientInterceptor.class);

    ChannelConfigurator configurer = new ChannelConfigurator() {
      @Override
      public void configureChannelBuilder(ManagedChannelBuilder<?> builder) {
        builder.intercept(interceptor1);
        builder.intercept(interceptor2);
      }
    };

    ManagedChannelBuilder<?> mockBuilder = mock(ManagedChannelBuilder.class);
    configurer.configureChannelBuilder(mockBuilder);

    verify(mockBuilder).intercept(interceptor1);
    verify(mockBuilder).intercept(interceptor2);
  }
}

