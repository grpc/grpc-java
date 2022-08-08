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

import static io.grpc.xds.AbstractXdsClient.ResourceType;
import static io.grpc.xds.AbstractXdsClient.ResourceType.CDS;
import static io.grpc.xds.AbstractXdsClient.ResourceType.EDS;
import static io.grpc.xds.Bootstrapper.BootstrapInfo;
import static io.grpc.xds.Bootstrapper.ServerInfo;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.config.cluster.v3.CircuitBreakers.Thresholds;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.RoutingPriority;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.xds.ClientXdsClient.ResourceInvalidException;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.XdsClient.CdsUpdate;
import io.grpc.xds.XdsClient.ResourceUpdate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;

class XdsClusterResource extends XdsResourceType {
  static final String ADS_TYPE_URL_CDS_V2 = "type.googleapis.com/envoy.api.v2.Cluster";
  static final String ADS_TYPE_URL_CDS =
      "type.googleapis.com/envoy.config.cluster.v3.Cluster";
  private static final String TYPE_URL_UPSTREAM_TLS_CONTEXT =
      "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext";
  private static final String TYPE_URL_UPSTREAM_TLS_CONTEXT_V2 =
      "type.googleapis.com/envoy.api.v2.auth.UpstreamTlsContext";
  @Nullable
  private final BootstrapInfo bootstrapInfo;
  private final LoadBalancerRegistry loadBalancerRegistry;

  public XdsClusterResource(SynchronizationContext syncContext,
                            @Nullable BootstrapInfo bootstrapInfo,
                            LoadBalancerRegistry loadBalancerRegistry) {
    super(syncContext);
    this.bootstrapInfo = bootstrapInfo;
    this.loadBalancerRegistry = loadBalancerRegistry;
  }

  @Override
  @Nullable
  String extractResourceName(Message unpackedResource) {
    if (!(unpackedResource instanceof Cluster)) {
      return null;
    }
    return ((Cluster) unpackedResource).getName();
  }

  @Override
  ResourceType typeName() {
    return CDS;
  }

  @Override
  String typeUrl() {
    return ADS_TYPE_URL_CDS;
  }

  @Override
  String typeUrlV2() {
    return ADS_TYPE_URL_CDS_V2;
  }

  @Nullable
  @Override
  ResourceType dependentResource() {
    return EDS;
  }

  @Override
  @SuppressWarnings("unchecked")
  Class<Cluster> unpackedClassName() {
    return Cluster.class;
  }

  @Override
  ResourceUpdate doParse(ServerInfo serverInfo, Message unpackedMessage,
                         Set<String> retainedResources, boolean isResourceV3)
      throws ResourceInvalidException {
    if (!(unpackedMessage instanceof Cluster)) {
      throw new ResourceInvalidException("Invalid message type: " + unpackedMessage.getClass());
    }
    Set<String> certProviderInstances = null;
    if (bootstrapInfo != null && bootstrapInfo.certProviders() != null) {
      certProviderInstances = bootstrapInfo.certProviders().keySet();
    }
    return processCluster((Cluster) unpackedMessage, retainedResources, certProviderInstances,
        serverInfo, loadBalancerRegistry);
  }

  @VisibleForTesting
  static CdsUpdate processCluster(Cluster cluster, Set<String> retainedEdsResources,
                                  Set<String> certProviderInstances,
                                  Bootstrapper.ServerInfo serverInfo,
                                  LoadBalancerRegistry loadBalancerRegistry)
      throws ResourceInvalidException {
    StructOrError<XdsClient.CdsUpdate.Builder> structOrError;
    switch (cluster.getClusterDiscoveryTypeCase()) {
      case TYPE:
        structOrError = parseNonAggregateCluster(cluster, retainedEdsResources,
            certProviderInstances, serverInfo);
        break;
      case CLUSTER_TYPE:
        structOrError = parseAggregateCluster(cluster);
        break;
      case CLUSTERDISCOVERYTYPE_NOT_SET:
      default:
        throw new ResourceInvalidException(
            "Cluster " + cluster.getName() + ": unspecified cluster discovery type");
    }
    if (structOrError.getErrorDetail() != null) {
      throw new ResourceInvalidException(structOrError.getErrorDetail());
    }
    XdsClient.CdsUpdate.Builder updateBuilder = structOrError.getStruct();

    ImmutableMap<String, ?> lbPolicyConfig = LoadBalancerConfigFactory.newConfig(cluster,
        enableLeastRequest, enableCustomLbConfig);

    // Validate the LB config by trying to parse it with the corresponding LB provider.
    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(lbPolicyConfig);
    NameResolver.ConfigOrError configOrError = loadBalancerRegistry.getProvider(
        lbConfig.getPolicyName()).parseLoadBalancingPolicyConfig(
        lbConfig.getRawConfigValue());
    if (configOrError.getError() != null) {
      throw new ResourceInvalidException(structOrError.getErrorDetail());
    }

    updateBuilder.lbPolicyConfig(lbPolicyConfig);

    return updateBuilder.build();
  }

  private static StructOrError<XdsClient.CdsUpdate.Builder> parseAggregateCluster(Cluster cluster) {
    String clusterName = cluster.getName();
    Cluster.CustomClusterType customType = cluster.getClusterType();
    String typeName = customType.getName();
    if (!typeName.equals(AGGREGATE_CLUSTER_TYPE_NAME)) {
      return StructOrError.fromError(
          "Cluster " + clusterName + ": unsupported custom cluster type: " + typeName);
    }
    io.envoyproxy.envoy.extensions.clusters.aggregate.v3.ClusterConfig clusterConfig;
    try {
      clusterConfig = unpackCompatibleType(customType.getTypedConfig(),
          io.envoyproxy.envoy.extensions.clusters.aggregate.v3.ClusterConfig.class,
          TYPE_URL_CLUSTER_CONFIG, TYPE_URL_CLUSTER_CONFIG_V2);
    } catch (InvalidProtocolBufferException e) {
      return StructOrError.fromError("Cluster " + clusterName + ": malformed ClusterConfig: " + e);
    }
    return StructOrError.fromStruct(XdsClient.CdsUpdate.forAggregate(
        clusterName, clusterConfig.getClustersList()));
  }

  private static StructOrError<XdsClient.CdsUpdate.Builder> parseNonAggregateCluster(
      Cluster cluster, Set<String> edsResources, Set<String> certProviderInstances,
      Bootstrapper.ServerInfo serverInfo) {
    String clusterName = cluster.getName();
    Bootstrapper.ServerInfo lrsServerInfo = null;
    Long maxConcurrentRequests = null;
    EnvoyServerProtoData.UpstreamTlsContext upstreamTlsContext = null;
    if (cluster.hasLrsServer()) {
      if (!cluster.getLrsServer().hasSelf()) {
        return StructOrError.fromError(
            "Cluster " + clusterName + ": only support LRS for the same management server");
      }
      lrsServerInfo = serverInfo;
    }
    if (cluster.hasCircuitBreakers()) {
      List<Thresholds> thresholds = cluster.getCircuitBreakers().getThresholdsList();
      for (Thresholds threshold : thresholds) {
        if (threshold.getPriority() != RoutingPriority.DEFAULT) {
          continue;
        }
        if (threshold.hasMaxRequests()) {
          maxConcurrentRequests = (long) threshold.getMaxRequests().getValue();
        }
      }
    }
    if (cluster.getTransportSocketMatchesCount() > 0) {
      return StructOrError.fromError("Cluster " + clusterName
          + ": transport-socket-matches not supported.");
    }
    if (cluster.hasTransportSocket()) {
      if (!TRANSPORT_SOCKET_NAME_TLS.equals(cluster.getTransportSocket().getName())) {
        return StructOrError.fromError("transport-socket with name "
            + cluster.getTransportSocket().getName() + " not supported.");
      }
      try {
        upstreamTlsContext = UpstreamTlsContext.fromEnvoyProtoUpstreamTlsContext(
            validateUpstreamTlsContext(
                unpackCompatibleType(cluster.getTransportSocket().getTypedConfig(),
                io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext.class,
                TYPE_URL_UPSTREAM_TLS_CONTEXT, TYPE_URL_UPSTREAM_TLS_CONTEXT_V2),
                certProviderInstances));
      } catch (InvalidProtocolBufferException | ResourceInvalidException e) {
        return StructOrError.fromError(
            "Cluster " + clusterName + ": malformed UpstreamTlsContext: " + e);
      }
    }

    Cluster.DiscoveryType type = cluster.getType();
    if (type == Cluster.DiscoveryType.EDS) {
      String edsServiceName = null;
      io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig edsClusterConfig =
          cluster.getEdsClusterConfig();
      if (!edsClusterConfig.getEdsConfig().hasAds()
          && ! edsClusterConfig.getEdsConfig().hasSelf()) {
        return StructOrError.fromError(
            "Cluster " + clusterName + ": field eds_cluster_config must be set to indicate to use"
                + " EDS over ADS or self ConfigSource");
      }
      // If the service_name field is set, that value will be used for the EDS request.
      if (!edsClusterConfig.getServiceName().isEmpty()) {
        edsServiceName = edsClusterConfig.getServiceName();
        edsResources.add(edsServiceName);
      } else {
        edsResources.add(clusterName);
      }
      return StructOrError.fromStruct(XdsClient.CdsUpdate.forEds(
          clusterName, edsServiceName, lrsServerInfo, maxConcurrentRequests, upstreamTlsContext));
    } else if (type.equals(Cluster.DiscoveryType.LOGICAL_DNS)) {
      if (!cluster.hasLoadAssignment()) {
        return StructOrError.fromError(
            "Cluster " + clusterName + ": LOGICAL_DNS clusters must have a single host");
      }
      ClusterLoadAssignment assignment = cluster.getLoadAssignment();
      if (assignment.getEndpointsCount() != 1
          || assignment.getEndpoints(0).getLbEndpointsCount() != 1) {
        return StructOrError.fromError(
            "Cluster " + clusterName + ": LOGICAL_DNS clusters must have a single "
                + "locality_lb_endpoint and a single lb_endpoint");
      }
      io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint lbEndpoint =
          assignment.getEndpoints(0).getLbEndpoints(0);
      if (!lbEndpoint.hasEndpoint() || !lbEndpoint.getEndpoint().hasAddress()
          || !lbEndpoint.getEndpoint().getAddress().hasSocketAddress()) {
        return StructOrError.fromError(
            "Cluster " + clusterName
                + ": LOGICAL_DNS clusters must have an endpoint with address and socket_address");
      }
      SocketAddress socketAddress = lbEndpoint.getEndpoint().getAddress().getSocketAddress();
      if (!socketAddress.getResolverName().isEmpty()) {
        return StructOrError.fromError(
            "Cluster " + clusterName
                + ": LOGICAL DNS clusters must NOT have a custom resolver name set");
      }
      if (socketAddress.getPortSpecifierCase() != SocketAddress.PortSpecifierCase.PORT_VALUE) {
        return StructOrError.fromError(
            "Cluster " + clusterName
                + ": LOGICAL DNS clusters socket_address must have port_value");
      }
      String dnsHostName = String.format(
          Locale.US, "%s:%d", socketAddress.getAddress(), socketAddress.getPortValue());
      return StructOrError.fromStruct(XdsClient.CdsUpdate.forLogicalDns(
          clusterName, dnsHostName, lrsServerInfo, maxConcurrentRequests, upstreamTlsContext));
    }
    return StructOrError.fromError(
        "Cluster " + clusterName + ": unsupported built-in discovery type: " + type);
  }

  @VisibleForTesting
  static io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext
      validateUpstreamTlsContext(
      io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext upstreamTlsContext,
      Set<String> certProviderInstances)
      throws ResourceInvalidException {
    if (upstreamTlsContext.hasCommonTlsContext()) {
      validateCommonTlsContext(upstreamTlsContext.getCommonTlsContext(), certProviderInstances,
          false);
    } else {
      throw new ResourceInvalidException("common-tls-context is required in upstream-tls-context");
    }
    return upstreamTlsContext;
  }
}
