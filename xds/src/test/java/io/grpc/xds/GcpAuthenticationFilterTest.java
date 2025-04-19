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
import static io.grpc.xds.XdsNameResolver.CLUSTER_SELECTION_KEY;
import static io.grpc.xds.XdsNameResolver.XDS_CONFIG_CALL_OPTION_KEY;
import static io.grpc.xds.XdsTestUtils.CLUSTER_NAME;
import static io.grpc.xds.XdsTestUtils.EDS_NAME;
import static io.grpc.xds.XdsTestUtils.ENDPOINT_HOSTNAME;
import static io.grpc.xds.XdsTestUtils.ENDPOINT_PORT;
import static io.grpc.xds.XdsTestUtils.RDS_NAME;
import static io.grpc.xds.XdsTestUtils.buildRouteConfiguration;
import static io.grpc.xds.XdsTestUtils.getWrrLbConfigAsMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.Empty;
import com.google.protobuf.Message;
import com.google.protobuf.UInt64Value;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.http.gcp_authn.v3.GcpAuthnFilterConfig;
import io.envoyproxy.envoy.extensions.filters.http.gcp_authn.v3.TokenCacheConfig;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.Endpoints.LbEndpoint;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.GcpAuthenticationFilter.AudienceMetadataParser.AudienceWrapper;
import io.grpc.xds.GcpAuthenticationFilter.FailingClientCall;
import io.grpc.xds.GcpAuthenticationFilter.GcpAuthenticationConfig;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.XdsConfig.XdsClusterConfig;
import io.grpc.xds.XdsConfig.XdsClusterConfig.EndpointConfig;
import io.grpc.xds.XdsEndpointResource.EdsUpdate;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import io.grpc.xds.client.Locality;
import io.grpc.xds.client.XdsResourceType;
import io.grpc.xds.client.XdsResourceType.ResourceInvalidException;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class GcpAuthenticationFilterTest {
  private static final GcpAuthenticationFilter.Provider FILTER_PROVIDER =
      new GcpAuthenticationFilter.Provider();
  private static final String serverName = InProcessServerBuilder.generateName();
  private static final LdsUpdate ldsUpdate = getLdsUpdate();
  private static final EdsUpdate edsUpdate = getEdsUpdate();
  private static final RdsUpdate rdsUpdate = getRdsUpdate();
  private static final CdsUpdate cdsUpdate = getCdsUpdate();

  @Test
  public void testNewFilterInstancesPerFilterName() {
    assertThat(new GcpAuthenticationFilter("FILTER_INSTANCE_NAME1", 10))
        .isNotEqualTo(new GcpAuthenticationFilter("FILTER_INSTANCE_NAME1", 10));
  }

  @Test
  public void filterType_clientOnly() {
    assertThat(FILTER_PROVIDER.isClientFilter()).isTrue();
    assertThat(FILTER_PROVIDER.isServerFilter()).isFalse();
  }

  @Test
  public void testParseFilterConfig_withValidConfig() {
    GcpAuthnFilterConfig config = GcpAuthnFilterConfig.newBuilder()
        .setCacheConfig(TokenCacheConfig.newBuilder().setCacheSize(UInt64Value.of(20)))
        .build();
    Any anyMessage = Any.pack(config);

    ConfigOrError<GcpAuthenticationConfig> result = FILTER_PROVIDER.parseFilterConfig(anyMessage);

    assertNotNull(result.config);
    assertNull(result.errorDetail);
    assertEquals(20L, result.config.getCacheSize());
  }

  @Test
  public void testParseFilterConfig_withZeroCacheSize() {
    GcpAuthnFilterConfig config = GcpAuthnFilterConfig.newBuilder()
        .setCacheConfig(TokenCacheConfig.newBuilder().setCacheSize(UInt64Value.of(0)))
        .build();
    Any anyMessage = Any.pack(config);

    ConfigOrError<GcpAuthenticationConfig> result = FILTER_PROVIDER.parseFilterConfig(anyMessage);

    assertNull(result.config);
    assertNotNull(result.errorDetail);
    assertTrue(result.errorDetail.contains("cache_config.cache_size must be greater than zero"));
  }

  @Test
  public void testParseFilterConfig_withInvalidMessageType() {
    Message invalidMessage = Empty.getDefaultInstance();
    ConfigOrError<GcpAuthenticationConfig> result =
        FILTER_PROVIDER.parseFilterConfig(invalidMessage);

    assertNull(result.config);
    assertThat(result.errorDetail).contains("Invalid config type");
  }

  @Test
  public void testClientInterceptor_success() throws IOException, ResourceInvalidException {
    XdsConfig.XdsClusterConfig clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME,
        cdsUpdate,
        new EndpointConfig(StatusOr.fromValue(edsUpdate)));
    XdsConfig defaultXdsConfig = new XdsConfig.XdsConfigBuilder()
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(rdsUpdate.virtualHosts.get(0))
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig)).build();
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster0")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);
    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME", 10);
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();
    Channel mockChannel = Mockito.mock(Channel.class);
    ArgumentCaptor<CallOptions> callOptionsCaptor = ArgumentCaptor.forClass(CallOptions.class);

    interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);

    verify(mockChannel).newCall(eq(methodDescriptor), callOptionsCaptor.capture());
    CallOptions capturedOptions = callOptionsCaptor.getAllValues().get(0);
    assertNotNull(capturedOptions.getCredentials());
  }

  @Test
  public void testClientInterceptor_createsAndReusesCachedCredentials()
      throws IOException, ResourceInvalidException {
    XdsConfig.XdsClusterConfig clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME,
        cdsUpdate,
        new EndpointConfig(StatusOr.fromValue(edsUpdate)));
    XdsConfig defaultXdsConfig = new XdsConfig.XdsConfigBuilder()
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(rdsUpdate.virtualHosts.get(0))
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig)).build();
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster0")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);
    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME", 10);
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();
    Channel mockChannel = Mockito.mock(Channel.class);
    ArgumentCaptor<CallOptions> callOptionsCaptor = ArgumentCaptor.forClass(CallOptions.class);

    interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);
    interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);

    verify(mockChannel, times(2))
        .newCall(eq(methodDescriptor), callOptionsCaptor.capture());
    CallOptions firstCapturedOptions = callOptionsCaptor.getAllValues().get(0);
    CallOptions secondCapturedOptions = callOptionsCaptor.getAllValues().get(1);
    assertNotNull(firstCapturedOptions.getCredentials());
    assertNotNull(secondCapturedOptions.getCredentials());
    assertSame(firstCapturedOptions.getCredentials(), secondCapturedOptions.getCredentials());
  }

  @Test
  public void testClientInterceptor_withoutClusterSelectionKey() throws Exception {
    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME", 10);
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();
    Channel mockChannel = mock(Channel.class);
    CallOptions callOptionsWithXds = CallOptions.DEFAULT;

    ClientCall<Void, Void> call = interceptor.interceptCall(
        methodDescriptor, callOptionsWithXds, mockChannel);

    assertTrue(call instanceof FailingClientCall);
    FailingClientCall<Void, Void> clientCall = (FailingClientCall<Void, Void>) call;
    assertThat(clientCall.error.getDescription()).contains("does not contain cluster resource");
  }

  @Test
  public void testClientInterceptor_clusterSelectionKeyWithoutPrefix() throws Exception {
    XdsConfig.XdsClusterConfig clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME,
        cdsUpdate,
        new EndpointConfig(StatusOr.fromValue(edsUpdate)));
    XdsConfig defaultXdsConfig = new XdsConfig.XdsConfigBuilder()
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(rdsUpdate.virtualHosts.get(0))
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig)).build();
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster0")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);
    Channel mockChannel = mock(Channel.class);

    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME", 10);
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();
    interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);

    verify(mockChannel).newCall(methodDescriptor, callOptionsWithXds);
  }

  @Test
  public void testClientInterceptor_xdsConfigDoesNotExist() throws Exception {
    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME", 10);
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();
    Channel mockChannel = mock(Channel.class);
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster0");

    ClientCall<Void, Void> call =
        interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);

    assertTrue(call instanceof FailingClientCall);
    FailingClientCall<Void, Void> clientCall = (FailingClientCall<Void, Void>) call;
    assertThat(clientCall.error.getDescription()).contains("does not contain xds configuration");
  }

  @Test
  public void testClientInterceptor_incorrectClusterName() throws Exception {
    XdsConfig.XdsClusterConfig clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME,
        cdsUpdate,
        new EndpointConfig(StatusOr.fromValue(edsUpdate)));
    XdsConfig defaultXdsConfig = new XdsConfig.XdsConfigBuilder()
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(rdsUpdate.virtualHosts.get(0))
        .addCluster("custer0", StatusOr.fromValue(clusterConfig)).build();
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);
    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME", 10);
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();
    Channel mockChannel = mock(Channel.class);

    ClientCall<Void, Void> call =
        interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);

    assertTrue(call instanceof FailingClientCall);
    FailingClientCall<Void, Void> clientCall = (FailingClientCall<Void, Void>) call;
    assertThat(clientCall.error.getDescription()).contains("does not contain xds cluster");
  }

  @Test
  public void testClientInterceptor_statusOrError() throws Exception {
    StatusOr<XdsClusterConfig> errorCluster =
        StatusOr.fromStatus(Status.NOT_FOUND.withDescription("Cluster resource not found"));
    XdsConfig defaultXdsConfig = new XdsConfig.XdsConfigBuilder()
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(rdsUpdate.virtualHosts.get(0))
        .addCluster(CLUSTER_NAME, errorCluster).build();
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster0")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);
    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME", 10);
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();
    Channel mockChannel = mock(Channel.class);

    ClientCall<Void, Void> call =
        interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);

    assertTrue(call instanceof FailingClientCall);
    FailingClientCall<Void, Void> clientCall = (FailingClientCall<Void, Void>) call;
    assertThat(clientCall.error.getDescription()).contains("Cluster resource not found");
  }

  @Test
  public void testClientInterceptor_notAudienceWrapper()
      throws IOException, ResourceInvalidException {
    XdsConfig.XdsClusterConfig clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME,
        getCdsUpdateWithIncorrectAudienceWrapper(),
        new EndpointConfig(StatusOr.fromValue(edsUpdate)));
    XdsConfig defaultXdsConfig = new XdsConfig.XdsConfigBuilder()
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(rdsUpdate.virtualHosts.get(0))
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig)).build();
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster0")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);
    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME", 10);
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();
    Channel mockChannel = Mockito.mock(Channel.class);

    ClientCall<Void, Void> call =
        interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);

    assertTrue(call instanceof FailingClientCall);
    FailingClientCall<Void, Void> clientCall = (FailingClientCall<Void, Void>) call;
    assertThat(clientCall.error.getDescription()).contains("GCP Authn found wrong type");
  }

  @Test
  public void testLruCacheAcrossInterceptors() throws IOException, ResourceInvalidException {
    XdsConfig.XdsClusterConfig clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME, cdsUpdate, new EndpointConfig(StatusOr.fromValue(edsUpdate)));
    XdsConfig defaultXdsConfig = new XdsConfig.XdsConfigBuilder()
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(rdsUpdate.virtualHosts.get(0))
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig)).build();
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster0")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME", 2);
    ClientInterceptor interceptor1
        = filter.buildClientInterceptor(new GcpAuthenticationConfig(2), null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();
    Channel mockChannel = Mockito.mock(Channel.class);
    ArgumentCaptor<CallOptions> callOptionsCaptor = ArgumentCaptor.forClass(CallOptions.class);

    interceptor1.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);
    verify(mockChannel).newCall(eq(methodDescriptor), callOptionsCaptor.capture());
    CallOptions capturedOptions1 = callOptionsCaptor.getAllValues().get(0);
    assertNotNull(capturedOptions1.getCredentials());
    ClientInterceptor interceptor2
        = filter.buildClientInterceptor(new GcpAuthenticationConfig(1), null, null);
    interceptor2.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);
    verify(mockChannel, times(2))
        .newCall(eq(methodDescriptor), callOptionsCaptor.capture());
    CallOptions capturedOptions2 = callOptionsCaptor.getAllValues().get(1);
    assertNotNull(capturedOptions2.getCredentials());

    assertSame(capturedOptions1.getCredentials(), capturedOptions2.getCredentials());
  }

  @Test
  public void testLruCacheEvictionOnResize() throws IOException, ResourceInvalidException {
    XdsConfig.XdsClusterConfig clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME, cdsUpdate, new EndpointConfig(StatusOr.fromValue(edsUpdate)));
    XdsConfig defaultXdsConfig = new XdsConfig.XdsConfigBuilder()
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(rdsUpdate.virtualHosts.get(0))
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig)).build();
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster0")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME", 2);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();

    ClientInterceptor interceptor1 =
        filter.buildClientInterceptor(new GcpAuthenticationConfig(2), null, null);
    Channel mockChannel1 = Mockito.mock(Channel.class);
    ArgumentCaptor<CallOptions> captor = ArgumentCaptor.forClass(CallOptions.class);
    interceptor1.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel1);
    verify(mockChannel1).newCall(eq(methodDescriptor), captor.capture());
    CallOptions options1 = captor.getValue();
    // This will recreate the cache with max size of 1 and copy the credential for audience1.
    ClientInterceptor interceptor2 =
        filter.buildClientInterceptor(new GcpAuthenticationConfig(1), null, null);
    Channel mockChannel2 = Mockito.mock(Channel.class);
    interceptor2.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel2);
    verify(mockChannel2).newCall(eq(methodDescriptor), captor.capture());
    CallOptions options2 = captor.getValue();

    assertSame(options1.getCredentials(), options2.getCredentials());

    clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME, getCdsUpdate2(), new EndpointConfig(StatusOr.fromValue(edsUpdate)));
    defaultXdsConfig = new XdsConfig.XdsConfigBuilder()
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(rdsUpdate.virtualHosts.get(0))
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig)).build();
    callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster0")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);

    // This will evict the credential for audience1 and add new credential for audience2
    ClientInterceptor interceptor3 =
        filter.buildClientInterceptor(new GcpAuthenticationConfig(1), null, null);
    Channel mockChannel3 = Mockito.mock(Channel.class);
    interceptor3.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel3);
    verify(mockChannel3).newCall(eq(methodDescriptor), captor.capture());
    CallOptions options3 = captor.getValue();

    assertNotSame(options1.getCredentials(), options3.getCredentials());

    clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME, cdsUpdate, new EndpointConfig(StatusOr.fromValue(edsUpdate)));
    defaultXdsConfig = new XdsConfig.XdsConfigBuilder()
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(rdsUpdate.virtualHosts.get(0))
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig)).build();
    callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster0")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);

    // This will create new credential for audience1 because it has been evicted
    ClientInterceptor interceptor4 =
        filter.buildClientInterceptor(new GcpAuthenticationConfig(1), null, null);
    Channel mockChannel4 = Mockito.mock(Channel.class);
    interceptor4.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel4);
    verify(mockChannel4).newCall(eq(methodDescriptor), captor.capture());
    CallOptions options4 = captor.getValue();

    assertNotSame(options1.getCredentials(), options4.getCredentials());
  }

  private static LdsUpdate getLdsUpdate() {
    Filter.NamedFilterConfig routerFilterConfig = new Filter.NamedFilterConfig(
        serverName, RouterFilter.ROUTER_CONFIG);
    HttpConnectionManager httpConnectionManager = HttpConnectionManager.forRdsName(
        0L, RDS_NAME, Collections.singletonList(routerFilterConfig));
    return XdsListenerResource.LdsUpdate.forApiListener(httpConnectionManager);
  }

  private static RdsUpdate getRdsUpdate() {
    RouteConfiguration routeConfiguration =
        buildRouteConfiguration(serverName, RDS_NAME, CLUSTER_NAME);
    XdsResourceType.Args args = new XdsResourceType.Args(null, "0", "0", null, null, null);
    try {
      return XdsRouteConfigureResource.getInstance().doParse(args, routeConfiguration);
    } catch (ResourceInvalidException ex) {
      return null;
    }
  }

  private static EdsUpdate getEdsUpdate() {
    Map<Locality, LocalityLbEndpoints> lbEndpointsMap = new HashMap<>();
    LbEndpoint lbEndpoint = LbEndpoint.create(
        serverName, ENDPOINT_PORT, 0, true, ENDPOINT_HOSTNAME, ImmutableMap.of());
    lbEndpointsMap.put(
        Locality.create("", "", ""),
        LocalityLbEndpoints.create(ImmutableList.of(lbEndpoint), 10, 0, ImmutableMap.of()));
    return new XdsEndpointResource.EdsUpdate(EDS_NAME, lbEndpointsMap, Collections.emptyList());
  }

  private static CdsUpdate getCdsUpdate() {
    ImmutableMap.Builder<String, Object> parsedMetadata = ImmutableMap.builder();
    parsedMetadata.put("FILTER_INSTANCE_NAME", new AudienceWrapper("TEST_AUDIENCE"));
    try {
      CdsUpdate.Builder cdsUpdate = CdsUpdate.forEds(
              CLUSTER_NAME, EDS_NAME, null, null, null, null, false)
          .lbPolicyConfig(getWrrLbConfigAsMap());
      return cdsUpdate.parsedMetadata(parsedMetadata.build()).build();
    } catch (IOException ex) {
      return null;
    }
  }

  private static CdsUpdate getCdsUpdate2() {
    ImmutableMap.Builder<String, Object> parsedMetadata = ImmutableMap.builder();
    parsedMetadata.put("FILTER_INSTANCE_NAME", new AudienceWrapper("NEW_TEST_AUDIENCE"));
    try {
      CdsUpdate.Builder cdsUpdate = CdsUpdate.forEds(
              CLUSTER_NAME, EDS_NAME, null, null, null, null, false)
          .lbPolicyConfig(getWrrLbConfigAsMap());
      return cdsUpdate.parsedMetadata(parsedMetadata.build()).build();
    } catch (IOException ex) {
      return null;
    }
  }

  private static CdsUpdate getCdsUpdateWithIncorrectAudienceWrapper() throws IOException {
    ImmutableMap.Builder<String, Object> parsedMetadata = ImmutableMap.builder();
    parsedMetadata.put("FILTER_INSTANCE_NAME", "TEST_AUDIENCE");
    CdsUpdate.Builder cdsUpdate = CdsUpdate.forEds(
            CLUSTER_NAME, EDS_NAME, null, null, null, null, false)
        .lbPolicyConfig(getWrrLbConfigAsMap());
    return cdsUpdate.parsedMetadata(parsedMetadata.build()).build();
  }
}
