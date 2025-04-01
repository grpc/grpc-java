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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
  public void testClientInterceptor_success()
      throws IOException, ResourceInvalidException {
    String serverName = InProcessServerBuilder.generateName();
    XdsConfig.XdsConfigBuilder builder = new XdsConfig.XdsConfigBuilder();

    Filter.NamedFilterConfig routerFilterConfig = new Filter.NamedFilterConfig(
        serverName, RouterFilter.ROUTER_CONFIG);

    HttpConnectionManager httpConnectionManager = HttpConnectionManager.forRdsName(
        0L, RDS_NAME, Collections.singletonList(routerFilterConfig));
    XdsListenerResource.LdsUpdate ldsUpdate =
        XdsListenerResource.LdsUpdate.forApiListener(httpConnectionManager);

    RouteConfiguration routeConfiguration =
        buildRouteConfiguration(serverName, RDS_NAME, CLUSTER_NAME);
    XdsResourceType.Args args = new XdsResourceType.Args(null, "0", "0", null, null, null);
    XdsRouteConfigureResource.RdsUpdate rdsUpdate =
        XdsRouteConfigureResource.getInstance().doParse(args, routeConfiguration);

    // Take advantage of knowing that there is only 1 virtual host in the route configuration
    assertThat(rdsUpdate.virtualHosts).hasSize(1);
    VirtualHost virtualHost = rdsUpdate.virtualHosts.get(0);

    // Need to create endpoints to create locality endpoints map to create edsUpdate
    Map<Locality, LocalityLbEndpoints> lbEndpointsMap = new HashMap<>();
    LbEndpoint lbEndpoint = LbEndpoint.create(
        serverName, ENDPOINT_PORT, 0, true, ENDPOINT_HOSTNAME, ImmutableMap.of());
    lbEndpointsMap.put(
        Locality.create("", "", ""),
        LocalityLbEndpoints.create(ImmutableList.of(lbEndpoint), 10, 0, ImmutableMap.of()));

    // Need to create EdsUpdate to create CdsUpdate to create XdsClusterConfig for builder
    XdsEndpointResource.EdsUpdate edsUpdate = new XdsEndpointResource.EdsUpdate(
        EDS_NAME, lbEndpointsMap, Collections.emptyList());

    // Use ImmutableMap.Builder to construct the map
    ImmutableMap.Builder<String, Object> parsedMetadata = ImmutableMap.builder();
    parsedMetadata.put("FILTER_INSTANCE_NAME", new AudienceWrapper("TEST_AUDIENCE"));

    CdsUpdate.Builder cdsUpdate = CdsUpdate.forEds(
            CLUSTER_NAME, EDS_NAME, null, null, null, null, false)
        .lbPolicyConfig(getWrrLbConfigAsMap());
    cdsUpdate.parsedMetadata(parsedMetadata.build());
    XdsConfig.XdsClusterConfig clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME,
        cdsUpdate.build(),
        new EndpointConfig(StatusOr.fromValue(edsUpdate)));

    XdsConfig defaultXdsConfig = builder
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(virtualHost)
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig)).build();
    // Set CallOptions with required keys
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster0")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);

    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME");

    // Create interceptor
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();

    // Mock channel and capture CallOptions
    Channel mockChannel = Mockito.mock(Channel.class);
    ArgumentCaptor<CallOptions> callOptionsCaptor = ArgumentCaptor.forClass(CallOptions.class);

    // Execute interception twice to check caching
    interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);

    // Capture and verify CallOptions for CallCredentials presence
    verify(mockChannel, Mockito.times(1))
        .newCall(eq(methodDescriptor), callOptionsCaptor.capture());

    // Retrieve the CallOptions captured from both calls
    CallOptions capturedOptions = callOptionsCaptor.getAllValues().get(0);

    // Ensure that CallCredentials was added
    assertNotNull(capturedOptions.getCredentials());
  }

  @Test
  public void testClientInterceptor_createsAndReusesCachedCredentials()
      throws IOException, ResourceInvalidException {
    String serverName = InProcessServerBuilder.generateName();
    XdsConfig.XdsConfigBuilder builder = new XdsConfig.XdsConfigBuilder();

    Filter.NamedFilterConfig routerFilterConfig = new Filter.NamedFilterConfig(
        serverName, RouterFilter.ROUTER_CONFIG);

    HttpConnectionManager httpConnectionManager = HttpConnectionManager.forRdsName(
        0L, RDS_NAME, Collections.singletonList(routerFilterConfig));
    XdsListenerResource.LdsUpdate ldsUpdate =
        XdsListenerResource.LdsUpdate.forApiListener(httpConnectionManager);

    RouteConfiguration routeConfiguration =
        buildRouteConfiguration(serverName, RDS_NAME, CLUSTER_NAME);
    XdsResourceType.Args args = new XdsResourceType.Args(null, "0", "0", null, null, null);
    XdsRouteConfigureResource.RdsUpdate rdsUpdate =
        XdsRouteConfigureResource.getInstance().doParse(args, routeConfiguration);

    // Take advantage of knowing that there is only 1 virtual host in the route configuration
    assertThat(rdsUpdate.virtualHosts).hasSize(1);
    VirtualHost virtualHost = rdsUpdate.virtualHosts.get(0);

    // Need to create endpoints to create locality endpoints map to create edsUpdate
    Map<Locality, LocalityLbEndpoints> lbEndpointsMap = new HashMap<>();
    LbEndpoint lbEndpoint = LbEndpoint.create(
        serverName, ENDPOINT_PORT, 0, true, ENDPOINT_HOSTNAME, ImmutableMap.of());
    lbEndpointsMap.put(
        Locality.create("", "", ""),
        LocalityLbEndpoints.create(ImmutableList.of(lbEndpoint), 10, 0, ImmutableMap.of()));

    // Need to create EdsUpdate to create CdsUpdate to create XdsClusterConfig for builder
    XdsEndpointResource.EdsUpdate edsUpdate = new XdsEndpointResource.EdsUpdate(
        EDS_NAME, lbEndpointsMap, Collections.emptyList());

    // Use ImmutableMap.Builder to construct the map
    ImmutableMap.Builder<String, Object> parsedMetadata = ImmutableMap.builder();
    parsedMetadata.put("FILTER_INSTANCE_NAME", new AudienceWrapper("TEST_AUDIENCE"));

    CdsUpdate.Builder cdsUpdate = CdsUpdate.forEds(
            CLUSTER_NAME, EDS_NAME, null, null, null, null, false)
        .lbPolicyConfig(getWrrLbConfigAsMap());
    cdsUpdate.parsedMetadata(parsedMetadata.build());
    XdsConfig.XdsClusterConfig clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME,
        cdsUpdate.build(),
        new EndpointConfig(StatusOr.fromValue(edsUpdate)));

    XdsConfig defaultXdsConfig = builder
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(virtualHost)
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig)).build();
    // Set CallOptions with required keys
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster0")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);

    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME");

    // Create interceptor
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();

    // Mock channel and capture CallOptions
    Channel mockChannel = Mockito.mock(Channel.class);
    ArgumentCaptor<CallOptions> callOptionsCaptor = ArgumentCaptor.forClass(CallOptions.class);

    // Execute interception twice to check caching
    interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);
    interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);

    // Capture and verify CallOptions for CallCredentials presence
    verify(mockChannel, Mockito.times(2))
        .newCall(eq(methodDescriptor), callOptionsCaptor.capture());

    // Retrieve the CallOptions captured from both calls
    CallOptions firstCapturedOptions = callOptionsCaptor.getAllValues().get(0);
    CallOptions secondCapturedOptions = callOptionsCaptor.getAllValues().get(1);

    // Ensure that CallCredentials was added
    assertNotNull(firstCapturedOptions.getCredentials());
    assertNotNull(secondCapturedOptions.getCredentials());

    // Ensure that the CallCredentials from both calls are the same, indicating caching
    assertSame(firstCapturedOptions.getCredentials(), secondCapturedOptions.getCredentials());
  }

  @Test
  public void testClientInterceptor_notAudienceWrapper()
      throws IOException, ResourceInvalidException {
    String serverName = InProcessServerBuilder.generateName();
    XdsConfig.XdsConfigBuilder builder = new XdsConfig.XdsConfigBuilder();

    Filter.NamedFilterConfig routerFilterConfig = new Filter.NamedFilterConfig(
        serverName, RouterFilter.ROUTER_CONFIG);

    HttpConnectionManager httpConnectionManager = HttpConnectionManager.forRdsName(
        0L, RDS_NAME, Collections.singletonList(routerFilterConfig));
    XdsListenerResource.LdsUpdate ldsUpdate =
        XdsListenerResource.LdsUpdate.forApiListener(httpConnectionManager);

    RouteConfiguration routeConfiguration =
        buildRouteConfiguration(serverName, RDS_NAME, CLUSTER_NAME);
    XdsResourceType.Args args = new XdsResourceType.Args(null, "0", "0", null, null, null);
    XdsRouteConfigureResource.RdsUpdate rdsUpdate =
        XdsRouteConfigureResource.getInstance().doParse(args, routeConfiguration);

    // Take advantage of knowing that there is only 1 virtual host in the route configuration
    assertThat(rdsUpdate.virtualHosts).hasSize(1);
    VirtualHost virtualHost = rdsUpdate.virtualHosts.get(0);

    // Need to create endpoints to create locality endpoints map to create edsUpdate
    Map<Locality, LocalityLbEndpoints> lbEndpointsMap = new HashMap<>();
    LbEndpoint lbEndpoint = LbEndpoint.create(
        serverName, ENDPOINT_PORT, 0, true, ENDPOINT_HOSTNAME, ImmutableMap.of());
    lbEndpointsMap.put(
        Locality.create("", "", ""),
        LocalityLbEndpoints.create(ImmutableList.of(lbEndpoint), 10, 0, ImmutableMap.of()));

    // Need to create EdsUpdate to create CdsUpdate to create XdsClusterConfig for builder
    XdsEndpointResource.EdsUpdate edsUpdate = new XdsEndpointResource.EdsUpdate(
        EDS_NAME, lbEndpointsMap, Collections.emptyList());

    // Use ImmutableMap.Builder to construct the map
    ImmutableMap.Builder<String, Object> parsedMetadata = ImmutableMap.builder();
    parsedMetadata.put("FILTER_INSTANCE_NAME", "TEST_AUDIENCE"); // not AudienceWrapper

    CdsUpdate.Builder cdsUpdate = CdsUpdate.forEds(
            CLUSTER_NAME, EDS_NAME, null, null, null, null, false)
        .lbPolicyConfig(getWrrLbConfigAsMap());
    cdsUpdate.parsedMetadata(parsedMetadata.build());
    XdsConfig.XdsClusterConfig clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME,
        cdsUpdate.build(),
        new EndpointConfig(StatusOr.fromValue(edsUpdate)));

    XdsConfig defaultXdsConfig = builder
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(virtualHost)
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig)).build();
    // Set CallOptions with required keys
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster0")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);

    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME");

    // Create interceptor
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();

    // Mock channel and capture CallOptions
    Channel mockChannel = Mockito.mock(Channel.class);

    ClientCall<Void, Void> call =
        interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);
    assertTrue(call instanceof FailingClientCall);
    FailingClientCall<Void, Void> clientCall = (FailingClientCall<Void, Void>) call;
    assertThat(clientCall.error.getDescription()).contains("GCP Authn found wrong type");
  }

  @Test
  public void testClientInterceptor_withoutClusterSelectionKey() throws Exception {
    String serverName = InProcessServerBuilder.generateName();

    RouteConfiguration routeConfiguration =
        buildRouteConfiguration(serverName, RDS_NAME, CLUSTER_NAME);
    XdsResourceType.Args args = new XdsResourceType.Args(null, "0", "0", null, null, null);
    XdsRouteConfigureResource.RdsUpdate rdsUpdate =
        XdsRouteConfigureResource.getInstance().doParse(args, routeConfiguration);

    // Take advantage of knowing that there is only 1 virtual host in the route configuration
    assertThat(rdsUpdate.virtualHosts).hasSize(1);

    // Use ImmutableMap.Builder to construct the map
    ImmutableMap.Builder<String, Object> parsedMetadata = ImmutableMap.builder();
    parsedMetadata.put("FILTER_INSTANCE_NAME", new AudienceWrapper("TEST_AUDIENCE"));

    CdsUpdate.Builder cdsUpdate = CdsUpdate.forEds(
            CLUSTER_NAME, EDS_NAME, null, null, null, null, false)
        .lbPolicyConfig(getWrrLbConfigAsMap());
    cdsUpdate.parsedMetadata(parsedMetadata.build());

    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME");

    // Create interceptor
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();

    // Mock channel and capture CallOptions
    Channel mockChannel = mock(Channel.class);

    // Set CallOptions with required keys
    CallOptions callOptionsWithXds = CallOptions.DEFAULT;

    // Execute interception twice to check caching
    ClientCall<Void, Void> call = interceptor.interceptCall(
        methodDescriptor, callOptionsWithXds, mockChannel);
    assertTrue(call instanceof FailingClientCall);
    FailingClientCall<Void, Void> clientCall = (FailingClientCall<Void, Void>) call;
    assertThat(clientCall.error.getDescription()).contains("does not contain cluster resource");
  }

  @Test
  public void testClientInterceptor_clusterSelectionKeyWithoutPrefix() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    XdsConfig.XdsConfigBuilder builder = new XdsConfig.XdsConfigBuilder();

    Filter.NamedFilterConfig routerFilterConfig = new Filter.NamedFilterConfig(
        serverName, RouterFilter.ROUTER_CONFIG);

    HttpConnectionManager httpConnectionManager = HttpConnectionManager.forRdsName(
        0L, RDS_NAME, Collections.singletonList(routerFilterConfig));
    XdsListenerResource.LdsUpdate ldsUpdate =
        XdsListenerResource.LdsUpdate.forApiListener(httpConnectionManager);

    RouteConfiguration routeConfiguration =
        buildRouteConfiguration(serverName, RDS_NAME, CLUSTER_NAME);
    XdsResourceType.Args args = new XdsResourceType.Args(null, "0", "0", null, null, null);
    XdsRouteConfigureResource.RdsUpdate rdsUpdate =
        XdsRouteConfigureResource.getInstance().doParse(args, routeConfiguration);

    // Take advantage of knowing that there is only 1 virtual host in the route configuration
    assertThat(rdsUpdate.virtualHosts).hasSize(1);
    VirtualHost virtualHost = rdsUpdate.virtualHosts.get(0);

    // Need to create endpoints to create locality endpoints map to create edsUpdate
    Map<Locality, LocalityLbEndpoints> lbEndpointsMap = new HashMap<>();
    LbEndpoint lbEndpoint = LbEndpoint.create(
        serverName, ENDPOINT_PORT, 0, true, ENDPOINT_HOSTNAME, ImmutableMap.of());
    lbEndpointsMap.put(
        Locality.create("", "", ""),
        LocalityLbEndpoints.create(ImmutableList.of(lbEndpoint), 10, 0, ImmutableMap.of()));

    // Need to create EdsUpdate to create CdsUpdate to create XdsClusterConfig for builder
    XdsEndpointResource.EdsUpdate edsUpdate = new XdsEndpointResource.EdsUpdate(
        EDS_NAME, lbEndpointsMap, Collections.emptyList());

    // Use ImmutableMap.Builder to construct the map
    ImmutableMap.Builder<String, Object> parsedMetadata = ImmutableMap.builder();
    parsedMetadata.put("FILTER_INSTANCE_NAME", new AudienceWrapper("TEST_AUDIENCE"));

    CdsUpdate.Builder cdsUpdate = CdsUpdate.forEds(
            CLUSTER_NAME, EDS_NAME, null, null, null, null, false)
        .lbPolicyConfig(getWrrLbConfigAsMap());
    cdsUpdate.parsedMetadata(parsedMetadata.build());
    XdsConfig.XdsClusterConfig clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME,
        cdsUpdate.build(),
        new EndpointConfig(StatusOr.fromValue(edsUpdate)));

    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME");

    // Create interceptor
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();

    // Mock channel and capture CallOptions
    Channel mockChannel = mock(Channel.class);

    XdsConfig defaultXdsConfig = builder
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(virtualHost)
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig)).build();
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster0")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);

    // Execute interception twice to check caching
    ClientCall<Void, Void> call =
        interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);
    assertFalse(call instanceof FailingClientCall);
    verify(mockChannel).newCall(methodDescriptor, callOptionsWithXds);
  }

  @Test
  public void testClientInterceptor_xdsConfigDoesNotExist() throws Exception {
    String serverName = InProcessServerBuilder.generateName();

    RouteConfiguration routeConfiguration =
        buildRouteConfiguration(serverName, RDS_NAME, CLUSTER_NAME);
    XdsResourceType.Args args = new XdsResourceType.Args(null, "0", "0", null, null, null);
    XdsRouteConfigureResource.RdsUpdate rdsUpdate =
        XdsRouteConfigureResource.getInstance().doParse(args, routeConfiguration);

    // Take advantage of knowing that there is only 1 virtual host in the route configuration
    assertThat(rdsUpdate.virtualHosts).hasSize(1);

    // Use ImmutableMap.Builder to construct the map
    ImmutableMap.Builder<String, Object> parsedMetadata = ImmutableMap.builder();
    parsedMetadata.put("FILTER_INSTANCE_NAME", new AudienceWrapper("TEST_AUDIENCE"));

    CdsUpdate.Builder cdsUpdate = CdsUpdate.forEds(
            CLUSTER_NAME, EDS_NAME, null, null, null, null, false)
        .lbPolicyConfig(getWrrLbConfigAsMap());
    cdsUpdate.parsedMetadata(parsedMetadata.build());

    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME");

    // Create interceptor
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();

    // Mock channel and capture CallOptions
    Channel mockChannel = mock(Channel.class);

    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster0");

    // Execute interception twice to check caching
    ClientCall<Void, Void> call =
        interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);
    assertTrue(call instanceof FailingClientCall);
    FailingClientCall<Void, Void> clientCall = (FailingClientCall<Void, Void>) call;
    assertThat(clientCall.error.getDescription()).contains("does not contain xds configuration");
  }

  @Test
  public void testClientInterceptor_incorrectClusterName() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    XdsConfig.XdsConfigBuilder builder = new XdsConfig.XdsConfigBuilder();

    Filter.NamedFilterConfig routerFilterConfig = new Filter.NamedFilterConfig(
        serverName, RouterFilter.ROUTER_CONFIG);

    HttpConnectionManager httpConnectionManager = HttpConnectionManager.forRdsName(
        0L, RDS_NAME, Collections.singletonList(routerFilterConfig));
    XdsListenerResource.LdsUpdate ldsUpdate =
        XdsListenerResource.LdsUpdate.forApiListener(httpConnectionManager);

    RouteConfiguration routeConfiguration =
        buildRouteConfiguration(serverName, RDS_NAME, CLUSTER_NAME);
    XdsResourceType.Args args = new XdsResourceType.Args(null, "0", "0", null, null, null);
    XdsRouteConfigureResource.RdsUpdate rdsUpdate =
        XdsRouteConfigureResource.getInstance().doParse(args, routeConfiguration);

    // Take advantage of knowing that there is only 1 virtual host in the route configuration
    assertThat(rdsUpdate.virtualHosts).hasSize(1);
    VirtualHost virtualHost = rdsUpdate.virtualHosts.get(0);

    // Need to create endpoints to create locality endpoints map to create edsUpdate
    Map<Locality, LocalityLbEndpoints> lbEndpointsMap = new HashMap<>();
    LbEndpoint lbEndpoint = LbEndpoint.create(
        serverName, ENDPOINT_PORT, 0, true, ENDPOINT_HOSTNAME, ImmutableMap.of());
    lbEndpointsMap.put(
        Locality.create("", "", ""),
        LocalityLbEndpoints.create(ImmutableList.of(lbEndpoint), 10, 0, ImmutableMap.of()));

    // Need to create EdsUpdate to create CdsUpdate to create XdsClusterConfig for builder
    XdsEndpointResource.EdsUpdate edsUpdate = new XdsEndpointResource.EdsUpdate(
        EDS_NAME, lbEndpointsMap, Collections.emptyList());

    // Use ImmutableMap.Builder to construct the map
    ImmutableMap.Builder<String, Object> parsedMetadata = ImmutableMap.builder();
    parsedMetadata.put("FILTER_INSTANCE_NAME", new AudienceWrapper("TEST_AUDIENCE"));

    CdsUpdate.Builder cdsUpdate = CdsUpdate.forEds(
            CLUSTER_NAME, EDS_NAME, null, null, null, null, false)
        .lbPolicyConfig(getWrrLbConfigAsMap());
    cdsUpdate.parsedMetadata(parsedMetadata.build());
    XdsConfig.XdsClusterConfig clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME,
        cdsUpdate.build(),
        new EndpointConfig(StatusOr.fromValue(edsUpdate)));

    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME");

    // Create interceptor
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();

    // Mock channel and capture CallOptions
    Channel mockChannel = mock(Channel.class);

    XdsConfig defaultXdsConfig = builder
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(virtualHost)
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig)).build();
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);

    // Execute interception twice to check caching
    ClientCall<Void, Void> call =
        interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);
    assertTrue(call instanceof FailingClientCall);
    FailingClientCall<Void, Void> clientCall = (FailingClientCall<Void, Void>) call;
    assertThat(clientCall.error.getDescription()).contains("does not contain xds cluster");
  }

  @Test
  public void testClientInterceptor_statusOrError() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    XdsConfig.XdsConfigBuilder builder = new XdsConfig.XdsConfigBuilder();

    Filter.NamedFilterConfig routerFilterConfig = new Filter.NamedFilterConfig(
        serverName, RouterFilter.ROUTER_CONFIG);

    HttpConnectionManager httpConnectionManager = HttpConnectionManager.forRdsName(
        0L, RDS_NAME, Collections.singletonList(routerFilterConfig));
    XdsListenerResource.LdsUpdate ldsUpdate =
        XdsListenerResource.LdsUpdate.forApiListener(httpConnectionManager);

    RouteConfiguration routeConfiguration =
        buildRouteConfiguration(serverName, RDS_NAME, CLUSTER_NAME);
    XdsResourceType.Args args = new XdsResourceType.Args(null, "0", "0", null, null, null);
    XdsRouteConfigureResource.RdsUpdate rdsUpdate =
        XdsRouteConfigureResource.getInstance().doParse(args, routeConfiguration);

    // Take advantage of knowing that there is only 1 virtual host in the route configuration
    assertThat(rdsUpdate.virtualHosts).hasSize(1);
    VirtualHost virtualHost = rdsUpdate.virtualHosts.get(0);

    // Use ImmutableMap.Builder to construct the map
    ImmutableMap.Builder<String, Object> parsedMetadata = ImmutableMap.builder();
    parsedMetadata.put("FILTER_INSTANCE_NAME", new AudienceWrapper("TEST_AUDIENCE"));

    CdsUpdate.Builder cdsUpdate = CdsUpdate.forEds(
            CLUSTER_NAME, EDS_NAME, null, null, null, null, false)
        .lbPolicyConfig(getWrrLbConfigAsMap());
    cdsUpdate.parsedMetadata(parsedMetadata.build());

    GcpAuthenticationConfig config = new GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter("FILTER_INSTANCE_NAME");

    // Create interceptor
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();

    // Mock channel and capture CallOptions
    Channel mockChannel = mock(Channel.class);

    StatusOr<XdsClusterConfig> errorCluster =
        StatusOr.fromStatus(Status.NOT_FOUND.withDescription("Cluster resource not found"));
    XdsConfig defaultXdsConfig = builder
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(virtualHost)
        .addCluster(CLUSTER_NAME, errorCluster).build();
    CallOptions callOptionsWithXds = CallOptions.DEFAULT
        .withOption(CLUSTER_SELECTION_KEY, "cluster:cluster0")
        .withOption(XDS_CONFIG_CALL_OPTION_KEY, defaultXdsConfig);

    // Execute interception twice to check caching
    ClientCall<Void, Void> call =
        interceptor.interceptCall(methodDescriptor, callOptionsWithXds, mockChannel);
    assertTrue(call instanceof FailingClientCall);
    FailingClientCall<Void, Void> clientCall = (FailingClientCall<Void, Void>) call;
    assertThat(clientCall.error.getDescription()).contains("Cluster resource not found");
  }
}
