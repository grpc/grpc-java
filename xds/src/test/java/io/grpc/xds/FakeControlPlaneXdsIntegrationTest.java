/*
 * Copyright 2021 The gRPC Authors
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
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_CDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_EDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_LDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_RDS;
import static org.junit.Assert.assertEquals;

import com.github.xds.type.v3.TypedStruct;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.protobuf.Struct;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.LoadBalancingPolicy;
import io.envoyproxy.envoy.config.cluster.v3.LoadBalancingPolicy.Policy;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.TrafficDirection;
import io.envoyproxy.envoy.config.core.v3.TypedExtensionConfig;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.NonForwardingAction;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.extensions.load_balancing_policies.wrr_locality.v3.WrrLocality;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolverRegistry;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Xds integration tests using a local control plane, implemented in {@link
 * XdsTestControlPlaneService}. Test cases can inject xds configs to the control plane for testing.
 */
@RunWith(JUnit4.class)
public class FakeControlPlaneXdsIntegrationTest {

  private static final Logger logger =
      Logger.getLogger(FakeControlPlaneXdsIntegrationTest.class.getName());
  private static final String SCHEME = "test-xds";
  private static final String SERVER_LISTENER_TEMPLATE_NO_REPLACEMENT =
      "grpc/server?udpa.resource.listening_address=";
  private static final String RDS_NAME = "route-config.googleapis.com";
  private static final String CLUSTER_NAME = "cluster0";
  private static final String EDS_NAME = "eds-service-0";
  private static final String HTTP_CONNECTION_MANAGER_TYPE_URL =
      "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3"
          + ".HttpConnectionManager";

  private Server server;
  private Server controlPlane;
  private XdsTestControlPlaneService controlPlaneService;
  private XdsNameResolverProvider nameResolverProvider;
  private MetadataLoadBalancerProvider metadataLoadBalancerProvider;

  protected int testServerPort = 0;
  protected int controlPlaneServicePort;
  protected SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub;

  /**
   * For test purpose, use boostrapOverride to programmatically provide bootstrap info.
   */
  private Map<String, ?> defaultBootstrapOverride() {
    return ImmutableMap.of(
        "node", ImmutableMap.of(
            "id", UUID.randomUUID().toString(),
            "cluster", "cluster0"),
        "xds_servers", Collections.singletonList(

            ImmutableMap.of(
                "server_uri", "localhost:" + controlPlaneServicePort,
                "channel_creds", Collections.singletonList(
                    ImmutableMap.of("type", "insecure")
                ),
                "server_features", Collections.singletonList("xds_v3")
            )
        ),
        "server_listener_resource_name_template", SERVER_LISTENER_TEMPLATE_NO_REPLACEMENT
    );
  }

  /**
   * 1. Start control plane server and get control plane port. 2. Start xdsServer using no
   * replacement server template, because we do not know the server port yet. Then get the server
   * port. 3. Update control plane config using the port in 2 for necessary rds and eds resources to
   * set up client and server communication for test cases.
   */
  @Before
  public void setUp() throws Exception {
    startControlPlane();
    nameResolverProvider = XdsNameResolverProvider.createForTest(SCHEME,
        defaultBootstrapOverride());
    NameResolverRegistry.getDefaultRegistry().register(nameResolverProvider);
    metadataLoadBalancerProvider = new MetadataLoadBalancerProvider();
    LoadBalancerRegistry.getDefaultRegistry().register(metadataLoadBalancerProvider);
  }

  @After
  public void tearDown() throws Exception {
    if (server != null) {
      server.shutdownNow();
      if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.log(Level.SEVERE, "Timed out waiting for server shutdown");
      }
    }
    if (controlPlane != null) {
      controlPlane.shutdownNow();
      if (!controlPlane.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.log(Level.SEVERE, "Timed out waiting for server shutdown");
      }
    }
    NameResolverRegistry.getDefaultRegistry().deregister(nameResolverProvider);
    LoadBalancerRegistry.getDefaultRegistry().deregister(metadataLoadBalancerProvider);
  }

  @Test
  public void pingPong() throws Exception {
    String tcpListenerName = SERVER_LISTENER_TEMPLATE_NO_REPLACEMENT;
    String serverHostName = "test-server";
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_LDS, ImmutableMap.of(
        tcpListenerName, serverListener(tcpListenerName),
        serverHostName, clientListener(serverHostName)
    ));
    startServer(defaultBootstrapOverride());
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_RDS,
        ImmutableMap.of(RDS_NAME, rds(serverHostName)));
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS,
        ImmutableMap.<String, Message>of(CLUSTER_NAME, cds()));
    InetSocketAddress edsInetSocketAddress = (InetSocketAddress) server.getListenSockets().get(0);
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS,
        ImmutableMap.<String, Message>of(EDS_NAME, eds(edsInetSocketAddress.getHostName(),
            edsInetSocketAddress.getPort())));
    ManagedChannel channel = Grpc.newChannelBuilder(SCHEME + ":///" + serverHostName,
        InsecureChannelCredentials.create()).build();
    blockingStub = SimpleServiceGrpc.newBlockingStub(channel);
    SimpleRequest request = SimpleRequest.newBuilder()
        .build();
    SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setResponseMessage("Hi, xDS!")
        .build();
    assertEquals(goldenResponse, blockingStub.unaryRpc(request));
  }

  @Test
  public void pingPong_metadataLoadBalancer() throws Exception {
    String tcpListenerName = SERVER_LISTENER_TEMPLATE_NO_REPLACEMENT;
    String serverHostName = "test-server";
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_LDS, ImmutableMap.of(
        tcpListenerName, serverListener(tcpListenerName),
        serverHostName, clientListener(serverHostName)
    ));
    startServer(defaultBootstrapOverride());
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_RDS,
        ImmutableMap.of(RDS_NAME, rds(serverHostName)));

    // Use the LoadBalancingPolicy to configure a custom LB that adds a header to server calls.
    Policy metadataLbPolicy = Policy.newBuilder().setTypedExtensionConfig(
        TypedExtensionConfig.newBuilder().setTypedConfig(Any.pack(
            TypedStruct.newBuilder().setTypeUrl("type.googleapis.com/test.MetadataLoadBalancer")
                .setValue(Struct.newBuilder()
                    .putFields("metadataKey", Value.newBuilder().setStringValue("foo").build())
                    .putFields("metadataValue", Value.newBuilder().setStringValue("bar").build()))
                .build()))).build();
    Policy wrrLocalityPolicy = Policy.newBuilder()
        .setTypedExtensionConfig(TypedExtensionConfig.newBuilder().setTypedConfig(
            Any.pack(WrrLocality.newBuilder().setEndpointPickingPolicy(
                LoadBalancingPolicy.newBuilder().addPolicies(metadataLbPolicy)).build()))).build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS,
        ImmutableMap.<String, Message>of(CLUSTER_NAME, cds().toBuilder().setLoadBalancingPolicy(
            LoadBalancingPolicy.newBuilder()
                .addPolicies(wrrLocalityPolicy)).build()));

    InetSocketAddress edsInetSocketAddress = (InetSocketAddress) server.getListenSockets().get(0);
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS,
        ImmutableMap.<String, Message>of(EDS_NAME, eds(edsInetSocketAddress.getHostName(),
            edsInetSocketAddress.getPort())));
    ManagedChannel channel = Grpc.newChannelBuilder(SCHEME + ":///" + serverHostName,
        InsecureChannelCredentials.create()).build();
    ResponseHeaderClientInterceptor responseHeaderInterceptor
        = new ResponseHeaderClientInterceptor();

    // We add an interceptor to catch the response headers from the server.
    blockingStub = SimpleServiceGrpc.newBlockingStub(channel)
        .withInterceptors(responseHeaderInterceptor);
    SimpleRequest request = SimpleRequest.newBuilder()
        .build();
    SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setResponseMessage("Hi, xDS!")
        .build();
    assertEquals(goldenResponse, blockingStub.unaryRpc(request));

    // Make sure we got back the header we configured the LB with.
    assertThat(responseHeaderInterceptor.reponseHeaders.get(
        Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER))).isEqualTo("bar");
  }

  // Captures response headers from the server.
  private class ResponseHeaderClientInterceptor implements ClientInterceptor {
    Metadata reponseHeaders;

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions, Channel next) {

      return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
        @Override
        public void start(ClientCall.Listener<RespT> responseListener, Metadata headers) {
          super.start(new ForwardingClientCallListener<RespT>() {
            @Override
            protected ClientCall.Listener<RespT> delegate() {
              return responseListener;
            }

            @Override
            public void onHeaders(Metadata headers) {
              reponseHeaders = headers;
            }
          }, headers);
        }
      };
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
    testServerPort = server.getPort();
    logger.log(Level.FINE, "server started");
  }

  private void startControlPlane() throws Exception {
    controlPlaneService = new XdsTestControlPlaneService();
    NettyServerBuilder controlPlaneServerBuilder =
        NettyServerBuilder.forPort(0)
            .addService(controlPlaneService);
    controlPlane = controlPlaneServerBuilder.build().start();
    controlPlaneServicePort = controlPlane.getPort();
  }

  private static Listener clientListener(String name) {
    HttpFilter httpFilter = HttpFilter.newBuilder()
        .setName("terminal-filter")
        .setTypedConfig(Any.pack(Router.newBuilder().build()))
        .setIsOptional(true)
        .build();
    ApiListener apiListener = ApiListener.newBuilder().setApiListener(Any.pack(
        io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3
            .HttpConnectionManager.newBuilder()
            .setRds(
                Rds.newBuilder()
                    .setRouteConfigName(RDS_NAME)
                    .setConfigSource(
                        ConfigSource.newBuilder()
                            .setAds(AggregatedConfigSource.getDefaultInstance())))
            .addAllHttpFilters(Collections.singletonList(httpFilter))
            .build(),
        HTTP_CONNECTION_MANAGER_TYPE_URL)).build();
    return Listener.newBuilder()
        .setName(name)
        .setApiListener(apiListener).build();
  }

  private static Listener serverListener(String name) {
    HttpFilter routerFilter = HttpFilter.newBuilder()
        .setName("terminal-filter")
        .setTypedConfig(
            Any.pack(Router.newBuilder().build()))
        .setIsOptional(true)
        .build();
    VirtualHost virtualHost = io.envoyproxy.envoy.config.route.v3.VirtualHost.newBuilder()
        .setName("virtual-host-0")
        .addDomains("*")
        .addRoutes(
            Route.newBuilder()
                .setMatch(
                    RouteMatch.newBuilder().setPrefix("/").build())
                .setNonForwardingAction(NonForwardingAction.newBuilder().build())
                .build()).build();
    RouteConfiguration routeConfig = RouteConfiguration.newBuilder()
        .addVirtualHosts(virtualHost)
        .build();
    Filter filter = Filter.newBuilder()
        .setName("network-filter-0")
        .setTypedConfig(
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(routeConfig)
                    .addAllHttpFilters(Collections.singletonList(routerFilter))
                    .build())).build();
    FilterChainMatch filterChainMatch = FilterChainMatch.newBuilder()
        .setSourceType(FilterChainMatch.ConnectionSourceType.ANY)
        .build();
    FilterChain filterChain = FilterChain.newBuilder()
        .setName("filter-chain-0")
        .setFilterChainMatch(filterChainMatch)
        .addFilters(filter)
        .build();
    return Listener.newBuilder()
        .setName(name)
        .setTrafficDirection(TrafficDirection.INBOUND)
        .addFilterChains(filterChain)
        .build();
  }

  private static RouteConfiguration rds(String authority) {
    VirtualHost virtualHost = VirtualHost.newBuilder()
        .addDomains(authority)
        .addRoutes(
            Route.newBuilder()
                .setMatch(
                    RouteMatch.newBuilder().setPrefix("/").build())
                .setRoute(
                    RouteAction.newBuilder().setCluster(CLUSTER_NAME).build()).build()).build();
    return RouteConfiguration.newBuilder().setName(RDS_NAME).addVirtualHosts(virtualHost).build();
  }

  private static Cluster cds() {
    return Cluster.newBuilder()
        .setName(CLUSTER_NAME)
        .setType(Cluster.DiscoveryType.EDS)
        .setEdsClusterConfig(
            Cluster.EdsClusterConfig.newBuilder()
                .setServiceName(EDS_NAME)
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.newBuilder().build())
                        .build())
                .build())
        .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
        .build();
  }

  private static ClusterLoadAssignment eds(String hostName, int port) {
    Address address = Address.newBuilder()
        .setSocketAddress(
            SocketAddress.newBuilder().setAddress(hostName).setPortValue(port).build()).build();
    LocalityLbEndpoints endpoints = LocalityLbEndpoints.newBuilder()
        .setLoadBalancingWeight(UInt32Value.of(10))
        .setPriority(0)
        .addLbEndpoints(
            LbEndpoint.newBuilder()
                .setEndpoint(
                    Endpoint.newBuilder().setAddress(address).build())
                .setHealthStatus(HealthStatus.HEALTHY)
                .build()).build();
    return ClusterLoadAssignment.newBuilder()
        .setClusterName(EDS_NAME)
        .addEndpoints(endpoints)
        .build();
  }
}
