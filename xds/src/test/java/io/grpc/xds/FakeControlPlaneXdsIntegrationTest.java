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
import static org.junit.Assert.assertEquals;

import com.github.xds.type.v3.TypedStruct;
import com.google.protobuf.Any;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.cluster.v3.LoadBalancingPolicy;
import io.envoyproxy.envoy.config.cluster.v3.LoadBalancingPolicy.Policy;
import io.envoyproxy.envoy.config.core.v3.TypedExtensionConfig;
import io.envoyproxy.envoy.extensions.load_balancing_policies.wrr_locality.v3.WrrLocality;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Xds integration tests using a local control plane, implemented in {@link
 * XdsTestControlPlaneService}. Test cases can inject xds configs to the control plane for testing.
 *
 * <p>Test components:
 * 1) A Control Plane {@link XdsTestControlPlaneService} accepts xds requests from multiple clients
 * from the Data Plane, see {@link ControlPlaneRule}.
 * 2) A test xDS server {@link XdsServerWrapper}, see {@link DataPlaneRule}.
 * 3) A test xDS client that uses a testing scheme {@link XdsNameResolverProvider#createForTest},
 * see {@link DataPlaneRule}.
 *
 * <p>The configuration dependency and ephemeral port allocation requires the components to
 * be initialized in a certain order:
 * 1) Start the Control Plane server {@link XdsTestControlPlaneService}. After start the bootstrap
 * information (w/ Control Plane's address) can be constructed for the Data Plane to initialize.
 * 2) Set LDS and RDS config at the Control Plane. Get the bootstrap file from the Control
 * Plane from 1). And then start the test xDS server (requires LDS/RDS and bootstrap file to start).
 * 3) Construct EDS config w/ test server address from 2). Set CDS and EDS Config at the Control
 * Plane. Then start the test xDS client (requires EDS to do xDS name resolution).
 */
@RunWith(JUnit4.class)
public class FakeControlPlaneXdsIntegrationTest {

  @Rule(order = 0)
  public ControlPlaneRule controlPlane = new ControlPlaneRule();
  @Rule(order = 1)
  public DataPlaneRule dataPlane = new DataPlaneRule(controlPlane);

  @Test
  public void pingPong() throws Exception {
    ManagedChannel channel = dataPlane.getManagedChannel();
    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub = SimpleServiceGrpc.newBlockingStub(
        channel);
    SimpleRequest request = SimpleRequest.newBuilder()
        .build();
    SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setResponseMessage("Hi, xDS!")
        .build();
    assertEquals(goldenResponse, blockingStub.unaryRpc(request));
  }

  @Test
  public void pingPong_metadataLoadBalancer() throws Exception {
    MetadataLoadBalancerProvider metadataLbProvider = new MetadataLoadBalancerProvider();
    try {
      LoadBalancerRegistry.getDefaultRegistry().register(metadataLbProvider);

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
                  LoadBalancingPolicy.newBuilder().addPolicies(metadataLbPolicy)).build())))
          .build();
      controlPlane.setCdsConfig(
          ControlPlaneRule.buildCluster().toBuilder().setLoadBalancingPolicy(
              LoadBalancingPolicy.newBuilder()
                  .addPolicies(wrrLocalityPolicy)).build());

      ResponseHeaderClientInterceptor responseHeaderInterceptor
          = new ResponseHeaderClientInterceptor();

      // We add an interceptor to catch the response headers from the server.
      SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub = SimpleServiceGrpc.newBlockingStub(
          dataPlane.getManagedChannel()).withInterceptors(responseHeaderInterceptor);
      SimpleRequest request = SimpleRequest.newBuilder()
          .build();
      SimpleResponse goldenResponse = SimpleResponse.newBuilder()
          .setResponseMessage("Hi, xDS!")
          .build();
      assertEquals(goldenResponse, blockingStub.unaryRpc(request));

      // Make sure we got back the header we configured the LB with.
      assertThat(responseHeaderInterceptor.reponseHeaders.get(
          Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER))).isEqualTo("bar");
    } finally {
      LoadBalancerRegistry.getDefaultRegistry().deregister(metadataLbProvider);
    }
  }

  // Captures response headers from the server.
  private static class ResponseHeaderClientInterceptor implements ClientInterceptor {
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

  /**
   * Basic test to make sure RING_HASH configuration works.
   */
  @Test
  public void pingPong_ringHash() {
    controlPlane.setCdsConfig(
        ControlPlaneRule.buildCluster().toBuilder()
            .setLbPolicy(LbPolicy.RING_HASH).build());

    ManagedChannel channel = dataPlane.getManagedChannel();
    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub = SimpleServiceGrpc.newBlockingStub(
        channel);
    SimpleRequest request = SimpleRequest.newBuilder()
        .build();
    SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setResponseMessage("Hi, xDS!")
        .build();
    assertEquals(goldenResponse, blockingStub.unaryRpc(request));
  }
}
