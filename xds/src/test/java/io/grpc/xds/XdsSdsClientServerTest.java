/*
 * Copyright 2019 The gRPC Authors
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
import static io.grpc.xds.XdsClientWrapperForServerSdsTest.buildFilterChainMatch;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.BAD_SERVER_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.BAD_SERVER_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_PEM_FILE;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators;
import io.grpc.xds.internal.sds.XdsChannelBuilder;
import io.grpc.xds.internal.sds.XdsServerBuilder;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link XdsChannelBuilder} and {@link XdsServerBuilder} for plaintext/TLS/mTLS
 * modes.
 */
@RunWith(JUnit4.class)
public class XdsSdsClientServerTest {

  @Rule public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  private int port;
  private FakeNameResolverFactory fakeNameResolverFactory;
  private XdsChannelBuilder builder;

  @Before
  public void setUp() throws IOException, URISyntaxException {
    MockitoAnnotations.initMocks(this);
    port = findFreePort();
    URI expectedUri = new URI("sdstest://localhost:" + port);
    fakeNameResolverFactory = new FakeNameResolverFactory.Builder(expectedUri).build();
    builder =
        XdsChannelBuilder.forTarget("sdstest://localhost:" + port)
            .nameResolverFactory(fakeNameResolverFactory);
  }

  @Test
  public void plaintextClientServer() throws IOException {
    Server unused = buildServerWithTlsContext(/* downstreamTlsContext= */ null);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(/* upstreamTlsContext= */ null, /* overrideAuthority= */ null);
    assertThat(unaryRpc("buddy", blockingStub)).isEqualTo("Hello buddy");
  }

  /** TLS channel - no mTLS. */
  @Test
  public void tlsClientServer_noClientAuthentication() throws IOException {
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextFromFilenames(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, null);
    Server unused = buildServerWithTlsContext(downstreamTlsContext);

    // for TLS, client only needs trustCa
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            /* privateKey= */ null, /* certChain= */ null, CA_PEM_FILE);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ "foo.test.google.fr");
    assertThat(unaryRpc(/* requestMessage= */ "buddy", blockingStub)).isEqualTo("Hello buddy");
  }

  /** mTLS - client auth enabled. */
  @Test
  public void mtlsClientServer_withClientAuthentication() throws IOException {
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);
    XdsClient.ListenerWatcher unused = performMtlsTestAndGetListenerWatcher(upstreamTlsContext);
  }

  /** mTLS - client auth enabled then update server certs to untrusted. */
  @Test
  public void mtlsClientServer_changeServerContext_expectException() throws IOException {
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);
    XdsClient.ListenerWatcher listenerWatcher =
        performMtlsTestAndGetListenerWatcher(upstreamTlsContext);
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextFromFilenames(
            BAD_SERVER_KEY_FILE, BAD_SERVER_PEM_FILE, CA_PEM_FILE);
    XdsClientWrapperForServerSdsTest.generateListenerUpdateToWatcher(
        port, downstreamTlsContext, listenerWatcher);
    try {
      SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
          getBlockingStub(upstreamTlsContext, "foo.test.google.fr");
      assertThat(unaryRpc("buddy", blockingStub)).isEqualTo("Hello buddy");
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasCauseThat().isInstanceOf(SSLHandshakeException.class);
      assertThat(sre).hasCauseThat().hasMessageThat().isEqualTo("General OpenSslEngine problem");
    }
  }

  private XdsClient.ListenerWatcher performMtlsTestAndGetListenerWatcher(
      UpstreamTlsContext upstreamTlsContext) throws IOException {
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextFromFilenames(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, CA_PEM_FILE);

    final XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        XdsClientWrapperForServerSdsTest.createXdsClientWrapperForServerSds(
            port, /* downstreamTlsContext= */ downstreamTlsContext);
    SdsProtocolNegotiators.ServerSdsProtocolNegotiator serverSdsProtocolNegotiator =
        new SdsProtocolNegotiators.ServerSdsProtocolNegotiator(xdsClientWrapperForServerSds);
    Server unused = getServer(port, serverSdsProtocolNegotiator);

    XdsClient.ListenerWatcher listenerWatcher = xdsClientWrapperForServerSds.getListenerWatcher();

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, "foo.test.google.fr");
    assertThat(unaryRpc("buddy", blockingStub)).isEqualTo("Hello buddy");
    return listenerWatcher;
  }

  private Server buildServerWithTlsContext(DownstreamTlsContext downstreamTlsContext)
      throws IOException {
    final XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        XdsClientWrapperForServerSdsTest.createXdsClientWrapperForServerSds(
            port, /* downstreamTlsContext= */ downstreamTlsContext);
    SdsProtocolNegotiators.ServerSdsProtocolNegotiator serverSdsProtocolNegotiator =
        new SdsProtocolNegotiators.ServerSdsProtocolNegotiator(xdsClientWrapperForServerSds);
    return getServer(port, serverSdsProtocolNegotiator);
  }

  private Server getServer(
      int port, SdsProtocolNegotiators.ServerSdsProtocolNegotiator serverSdsProtocolNegotiator)
      throws IOException {
    XdsServerBuilder builder = XdsServerBuilder.forPort(port).addService(new SimpleServiceImpl());
    return cleanupRule.register(builder.buildServer(serverSdsProtocolNegotiator)).start();
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  static EnvoyServerProtoData.Listener buildListener(
      String name, String address, int port, DownstreamTlsContext tlsContext) {
    EnvoyServerProtoData.FilterChainMatch filterChainMatch = buildFilterChainMatch(port, address);
    EnvoyServerProtoData.FilterChain filterChain1 =
        new EnvoyServerProtoData.FilterChain(filterChainMatch, tlsContext);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(name, address, Arrays.asList(filterChain1));
    return listener;
  }

  private SimpleServiceGrpc.SimpleServiceBlockingStub getBlockingStub(
      final UpstreamTlsContext upstreamTlsContext, String overrideAuthority) {
    if (overrideAuthority != null) {
      builder = builder.overrideAuthority(overrideAuthority);
    }
    InetSocketAddress socketAddress =
        new InetSocketAddress(Inet4Address.getLoopbackAddress(), port);
    Attributes attrs =
        (upstreamTlsContext != null)
            ? Attributes.newBuilder()
                .set(XdsAttributes.ATTR_UPSTREAM_TLS_CONTEXT, upstreamTlsContext)
                .build()
            : Attributes.EMPTY;
    fakeNameResolverFactory.setServers(
        Collections.singletonList(new EquivalentAddressGroup(socketAddress, attrs)));
    return SimpleServiceGrpc.newBlockingStub(cleanupRule.register(builder.build()));
  }

  /** Say hello to server. */
  private static String unaryRpc(
      String requestMessage, SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub) {
    SimpleRequest request = SimpleRequest.newBuilder().setRequestMessage(requestMessage).build();
    SimpleResponse response = blockingStub.unaryRpc(request);
    return response.getResponseMessage();
  }

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {

    @Override
    public void unaryRpc(SimpleRequest req, StreamObserver<SimpleResponse> responseObserver) {
      SimpleResponse response =
          SimpleResponse.newBuilder()
              .setResponseMessage("Hello " + req.getRequestMessage())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  private static final class FakeNameResolverFactory extends NameResolver.Factory {
    final URI expectedUri;
    List<EquivalentAddressGroup> servers = ImmutableList.of();
    final boolean resolvedAtStart;
    final Status error;
    final ArrayList<FakeNameResolver> resolvers = new ArrayList<>();
    final AtomicReference<ConfigOrError> nextConfigOrError = new AtomicReference<>();

    FakeNameResolverFactory(URI expectedUri, boolean resolvedAtStart, Status error) {
      this.expectedUri = expectedUri;
      this.resolvedAtStart = resolvedAtStart;
      this.error = error;
    }

    void setServers(List<EquivalentAddressGroup> servers) {
      this.servers = servers;
    }

    @Override
    public NameResolver newNameResolver(final URI targetUri, NameResolver.Args args) {
      if (!expectedUri.equals(targetUri)) {
        return null;
      }
      FakeNameResolver resolver = new FakeNameResolver(error);
      resolvers.add(resolver);
      return resolver;
    }

    @Override
    public String getDefaultScheme() {
      return "fake";
    }

    void allResolved() {
      for (FakeNameResolver resolver : resolvers) {
        resolver.resolved();
      }
    }

    final class FakeNameResolver extends NameResolver {
      Listener2 listener;
      boolean shutdown;
      int refreshCalled;
      Status error;

      FakeNameResolver(Status error) {
        this.error = error;
      }

      @Override
      public String getServiceAuthority() {
        return expectedUri.getAuthority();
      }

      @Override
      public void start(Listener2 listener) {
        this.listener = listener;
        if (resolvedAtStart) {
          resolved();
        }
      }

      @Override
      public void refresh() {
        refreshCalled++;
        resolved();
      }

      void resolved() {
        if (error != null) {
          listener.onError(error);
          return;
        }
        ResolutionResult.Builder builder = ResolutionResult.newBuilder().setAddresses(servers);
        ConfigOrError configOrError = nextConfigOrError.get();
        if (configOrError != null) {
          builder.setServiceConfig(configOrError);
        }
        listener.onResult(builder.build());
      }

      @Override
      public void shutdown() {
        shutdown = true;
      }

      @Override
      public String toString() {
        return "FakeNameResolver";
      }
    }

    static final class Builder {
      final URI expectedUri;
      boolean resolvedAtStart = true;
      Status error = null;

      Builder(URI expectedUri) {
        this.expectedUri = expectedUri;
      }

      Builder setResolvedAtStart(boolean resolvedAtStart) {
        this.resolvedAtStart = resolvedAtStart;
        return this;
      }

      Builder setError(Status error) {
        this.error = error;
        return this;
      }

      FakeNameResolverFactory build() {
        return new FakeNameResolverFactory(expectedUri, resolvedAtStart, error);
      }
    }
  }
}
