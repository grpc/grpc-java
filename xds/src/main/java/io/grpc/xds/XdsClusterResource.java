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

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.xds.client.Bootstrapper.ServerInfo;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Struct;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.config.cluster.v3.CircuitBreakers.Thresholds;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.RoutingPriority;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.extensions.transport_sockets.http_11_proxy.v3.Http11ProxyUpstreamTransport;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.xds.EnvoyServerProtoData.OutlierDetection;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.client.XdsClient.ResourceUpdate;
import io.grpc.xds.client.XdsResourceType;
import io.grpc.xds.internal.security.CommonTlsContextUtil;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;

class XdsClusterResource extends XdsResourceType<CdsUpdate> {
  @VisibleForTesting
  static boolean enableLeastRequest =
      !Strings.isNullOrEmpty(System.getenv("GRPC_EXPERIMENTAL_ENABLE_LEAST_REQUEST"))
          ? Boolean.parseBoolean(System.getenv("GRPC_EXPERIMENTAL_ENABLE_LEAST_REQUEST"))
          : Boolean.parseBoolean(System.getProperty("io.grpc.xds.experimentalEnableLeastRequest"));
  @VisibleForTesting
  public static boolean enableSystemRootCerts =
      GrpcUtil.getFlag("GRPC_EXPERIMENTAL_XDS_SYSTEM_ROOT_CERTS", false);
  static boolean isEnabledXdsHttpConnect =
      GrpcUtil.getFlag("GRPC_EXPERIMENTAL_XDS_HTTP_CONNECT", false);

  @VisibleForTesting
  static final String AGGREGATE_CLUSTER_TYPE_NAME = "envoy.clusters.aggregate";
  static final String ADS_TYPE_URL_CDS =
      "type.googleapis.com/envoy.config.cluster.v3.Cluster";
  private static final String TYPE_URL_CLUSTER_CONFIG =
      "type.googleapis.com/envoy.extensions.clusters.aggregate.v3.ClusterConfig";
  private static final String TYPE_URL_UPSTREAM_TLS_CONTEXT =
      "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext";
  private static final String TYPE_URL_UPSTREAM_TLS_CONTEXT_V2 =
      "type.googleapis.com/envoy.api.v2.auth.UpstreamTlsContext";
  static final String TRANSPORT_SOCKET_NAME_HTTP11_PROXY =
      "type.googleapis.com/envoy.extensions.transport_sockets.http_11_proxy.v3"
          + ".Http11ProxyUpstreamTransport";
  private final LoadBalancerRegistry loadBalancerRegistry
      = LoadBalancerRegistry.getDefaultRegistry();

  private static final XdsClusterResource instance = new XdsClusterResource();

  public static XdsClusterResource getInstance() {
    return instance;
  }

  @Override
  @Nullable
  protected String extractResourceName(Message unpackedResource) {
    if (!(unpackedResource instanceof Cluster)) {
      return null;
    }
    return ((Cluster) unpackedResource).getName();
  }

  @Override
  public String typeName() {
    return "CDS";
  }

  @Override
  public String typeUrl() {
    return ADS_TYPE_URL_CDS;
  }

  @Override
  public boolean shouldRetrieveResourceKeysForArgs() {
    return true;
  }

  @Override
  protected boolean isFullStateOfTheWorld() {
    return true;
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Class<Cluster> unpackedClassName() {
    return Cluster.class;
  }

  @Override
  protected CdsUpdate doParse(Args args, Message unpackedMessage) throws ResourceInvalidException {
    if (!(unpackedMessage instanceof Cluster)) {
      throw new ResourceInvalidException("Invalid message type: " + unpackedMessage.getClass());
    }
    Set<String> certProviderInstances = null;
    if (args.getBootstrapInfo() != null && args.getBootstrapInfo().certProviders() != null) {
      certProviderInstances = args.getBootstrapInfo().certProviders().keySet();
    }
    return processCluster((Cluster) unpackedMessage, certProviderInstances,
        args.getServerInfo(), loadBalancerRegistry);
  }

  @VisibleForTesting
  static CdsUpdate processCluster(Cluster cluster,
                                  Set<String> certProviderInstances,
                                  ServerInfo serverInfo,
                                  LoadBalancerRegistry loadBalancerRegistry)
      throws ResourceInvalidException {
    StructOrError<CdsUpdate.Builder> structOrError;
    switch (cluster.getClusterDiscoveryTypeCase()) {
      case TYPE:
        structOrError = parseNonAggregateCluster(cluster,
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
    CdsUpdate.Builder updateBuilder = structOrError.getStruct();

    ImmutableMap<String, ?> lbPolicyConfig = LoadBalancerConfigFactory.newConfig(cluster,
        enableLeastRequest);

    // Validate the LB config by trying to parse it with the corresponding LB provider.
    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(lbPolicyConfig);
    NameResolver.ConfigOrError configOrError = loadBalancerRegistry.getProvider(
        lbConfig.getPolicyName()).parseLoadBalancingPolicyConfig(
        lbConfig.getRawConfigValue());
    if (configOrError.getError() != null) {
      throw new ResourceInvalidException(structOrError.getErrorDetail());
    }

    updateBuilder.lbPolicyConfig(lbPolicyConfig);
    updateBuilder.filterMetadata(
        ImmutableMap.copyOf(cluster.getMetadata().getFilterMetadataMap()));

    try {
      MetadataRegistry registry = MetadataRegistry.getInstance();
      ImmutableMap<String, Object> parsedFilterMetadata =
          registry.parseMetadata(cluster.getMetadata());
      updateBuilder.parsedMetadata(parsedFilterMetadata);
    } catch (ResourceInvalidException e) {
      throw new ResourceInvalidException(
          "Failed to parse xDS filter metadata for cluster '" + cluster.getName() + "': "
              + e.getMessage(), e);
    }

    return updateBuilder.build();
  }

  private static StructOrError<CdsUpdate.Builder> parseAggregateCluster(Cluster cluster) {
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
          TYPE_URL_CLUSTER_CONFIG, null);
    } catch (InvalidProtocolBufferException e) {
      return StructOrError.fromError("Cluster " + clusterName + ": malformed ClusterConfig: " + e);
    }
    return StructOrError.fromStruct(CdsUpdate.forAggregate(
        clusterName, clusterConfig.getClustersList()));
  }

  private static StructOrError<CdsUpdate.Builder> parseNonAggregateCluster(
      Cluster cluster, Set<String> certProviderInstances, ServerInfo serverInfo) {
    String clusterName = cluster.getName();
    ServerInfo lrsServerInfo = null;
    Long maxConcurrentRequests = null;
    UpstreamTlsContext upstreamTlsContext = null;
    OutlierDetection outlierDetection = null;
    boolean isHttp11ProxyAvailable = false;
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
          maxConcurrentRequests = Integer.toUnsignedLong(threshold.getMaxRequests().getValue());
        }
      }
    }
    if (cluster.getTransportSocketMatchesCount() > 0) {
      return StructOrError.fromError("Cluster " + clusterName
          + ": transport-socket-matches not supported.");
    }
    boolean hasTransportSocket = cluster.hasTransportSocket();
    TransportSocket transportSocket = cluster.getTransportSocket();

    if (hasTransportSocket && !TRANSPORT_SOCKET_NAME_TLS.equals(transportSocket.getName())
        && !(isEnabledXdsHttpConnect
        && TRANSPORT_SOCKET_NAME_HTTP11_PROXY.equals(transportSocket.getName()))) {
      return StructOrError.fromError(
          "transport-socket with name " + transportSocket.getName() + " not supported.");
    }

    if (hasTransportSocket && isEnabledXdsHttpConnect
        && TRANSPORT_SOCKET_NAME_HTTP11_PROXY.equals(transportSocket.getName())) {
      isHttp11ProxyAvailable = true;
      try {
        Http11ProxyUpstreamTransport wrappedTransportSocket = transportSocket
            .getTypedConfig().unpack(io.envoyproxy.envoy.extensions.transport_sockets
                .http_11_proxy.v3.Http11ProxyUpstreamTransport.class);
        hasTransportSocket = wrappedTransportSocket.hasTransportSocket();
        transportSocket = wrappedTransportSocket.getTransportSocket();
      } catch (InvalidProtocolBufferException e) {
        return StructOrError.fromError(
            "Cluster " + clusterName + ": malformed Http11ProxyUpstreamTransport: " + e);
      } catch (ClassCastException e) {
        return StructOrError.fromError(
            "Cluster " + clusterName
                + ": invalid transport_socket type in Http11ProxyUpstreamTransport");
      }
    }

    if (hasTransportSocket && TRANSPORT_SOCKET_NAME_TLS.equals(transportSocket.getName())) {
      try {
        upstreamTlsContext = UpstreamTlsContext.fromEnvoyProtoUpstreamTlsContext(
            validateUpstreamTlsContext(
                unpackCompatibleType(transportSocket.getTypedConfig(),
                    io.envoyproxy.envoy.extensions
                        .transport_sockets.tls.v3.UpstreamTlsContext.class,
                    TYPE_URL_UPSTREAM_TLS_CONTEXT, TYPE_URL_UPSTREAM_TLS_CONTEXT_V2),
                certProviderInstances));
      } catch (InvalidProtocolBufferException | ResourceInvalidException e) {
        return StructOrError.fromError(
            "Cluster " + clusterName + ": malformed UpstreamTlsContext: " + e);
      }
    }

    if (cluster.hasOutlierDetection()) {
      try {
        outlierDetection = OutlierDetection.fromEnvoyOutlierDetection(
            validateOutlierDetection(cluster.getOutlierDetection()));
      } catch (ResourceInvalidException e) {
        return StructOrError.fromError(
            "Cluster " + clusterName + ": malformed outlier_detection: " + e);
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
      }
      // edsServiceName is required if the CDS resource has an xdstp name.
      if ((edsServiceName == null) && clusterName.toLowerCase(Locale.ROOT).startsWith("xdstp:")) {
        return StructOrError.fromError(
            "EDS service_name must be set when Cluster resource has an xdstp name");
      }

      return StructOrError.fromStruct(CdsUpdate.forEds(
          clusterName, edsServiceName, lrsServerInfo, maxConcurrentRequests, upstreamTlsContext,
          outlierDetection, isHttp11ProxyAvailable));
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
      return StructOrError.fromStruct(CdsUpdate.forLogicalDns(
          clusterName, dnsHostName, lrsServerInfo, maxConcurrentRequests,
          upstreamTlsContext, isHttp11ProxyAvailable));
    }
    return StructOrError.fromError(
        "Cluster " + clusterName + ": unsupported built-in discovery type: " + type);
  }

  static io.envoyproxy.envoy.config.cluster.v3.OutlierDetection validateOutlierDetection(
      io.envoyproxy.envoy.config.cluster.v3.OutlierDetection outlierDetection)
      throws ResourceInvalidException {
    if (outlierDetection.hasInterval()) {
      if (!Durations.isValid(outlierDetection.getInterval())) {
        throw new ResourceInvalidException("outlier_detection interval is not a valid Duration");
      }
      if (hasNegativeValues(outlierDetection.getInterval())) {
        throw new ResourceInvalidException("outlier_detection interval has a negative value");
      }
    }
    if (outlierDetection.hasBaseEjectionTime()) {
      if (!Durations.isValid(outlierDetection.getBaseEjectionTime())) {
        throw new ResourceInvalidException(
            "outlier_detection base_ejection_time is not a valid Duration");
      }
      if (hasNegativeValues(outlierDetection.getBaseEjectionTime())) {
        throw new ResourceInvalidException(
            "outlier_detection base_ejection_time has a negative value");
      }
    }
    if (outlierDetection.hasMaxEjectionTime()) {
      if (!Durations.isValid(outlierDetection.getMaxEjectionTime())) {
        throw new ResourceInvalidException(
            "outlier_detection max_ejection_time is not a valid Duration");
      }
      if (hasNegativeValues(outlierDetection.getMaxEjectionTime())) {
        throw new ResourceInvalidException(
            "outlier_detection max_ejection_time has a negative value");
      }
    }
    if (outlierDetection.hasMaxEjectionPercent()
        && outlierDetection.getMaxEjectionPercent().getValue() > 100) {
      throw new ResourceInvalidException(
          "outlier_detection max_ejection_percent is > 100");
    }
    if (outlierDetection.hasEnforcingSuccessRate()
        && outlierDetection.getEnforcingSuccessRate().getValue() > 100) {
      throw new ResourceInvalidException(
          "outlier_detection enforcing_success_rate is > 100");
    }
    if (outlierDetection.hasFailurePercentageThreshold()
        && outlierDetection.getFailurePercentageThreshold().getValue() > 100) {
      throw new ResourceInvalidException(
          "outlier_detection failure_percentage_threshold is > 100");
    }
    if (outlierDetection.hasEnforcingFailurePercentage()
        && outlierDetection.getEnforcingFailurePercentage().getValue() > 100) {
      throw new ResourceInvalidException(
          "outlier_detection enforcing_failure_percentage is > 100");
    }

    return outlierDetection;
  }

  static boolean hasNegativeValues(Duration duration) {
    return duration.getSeconds() < 0 || duration.getNanos() < 0;
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

  @VisibleForTesting
  static void validateCommonTlsContext(
      CommonTlsContext commonTlsContext, Set<String> certProviderInstances, boolean server)
      throws ResourceInvalidException {
    if (commonTlsContext.hasCustomHandshaker()) {
      throw new ResourceInvalidException(
          "common-tls-context with custom_handshaker is not supported");
    }
    if (commonTlsContext.hasTlsParams()) {
      throw new ResourceInvalidException("common-tls-context with tls_params is not supported");
    }
    if (commonTlsContext.hasValidationContextSdsSecretConfig()) {
      throw new ResourceInvalidException(
          "common-tls-context with validation_context_sds_secret_config is not supported");
    }
    String certInstanceName = getIdentityCertInstanceName(commonTlsContext);
    if (certInstanceName == null) {
      if (server) {
        throw new ResourceInvalidException(
            "tls_certificate_provider_instance is required in downstream-tls-context");
      }
      if (commonTlsContext.getTlsCertificatesCount() > 0) {
        throw new ResourceInvalidException(
            "tls_certificate_provider_instance is unset");
      }
      if (commonTlsContext.getTlsCertificateSdsSecretConfigsCount() > 0) {
        throw new ResourceInvalidException(
            "tls_certificate_provider_instance is unset");
      }
    } else if (certProviderInstances == null || !certProviderInstances.contains(certInstanceName)) {
      throw new ResourceInvalidException(
          "CertificateProvider instance name '" + certInstanceName
              + "' not defined in the bootstrap file.");
    }
    String rootCaInstanceName = getRootCertInstanceName(commonTlsContext);
    if (rootCaInstanceName == null) {
      if (!server && (!enableSystemRootCerts
          || !CommonTlsContextUtil.isUsingSystemRootCerts(commonTlsContext))) {
        throw new ResourceInvalidException(
            "ca_certificate_provider_instance or system_root_certs is required in "
                + "upstream-tls-context");
      }
    } else {
      if (certProviderInstances == null || !certProviderInstances.contains(rootCaInstanceName)) {
        throw new ResourceInvalidException(
            "ca_certificate_provider_instance name '" + rootCaInstanceName
                + "' not defined in the bootstrap file.");
      }
      CertificateValidationContext certificateValidationContext = null;
      if (commonTlsContext.hasValidationContext()) {
        certificateValidationContext = commonTlsContext.getValidationContext();
      } else if (commonTlsContext.hasCombinedValidationContext() && commonTlsContext
          .getCombinedValidationContext().hasDefaultValidationContext()) {
        certificateValidationContext = commonTlsContext.getCombinedValidationContext()
            .getDefaultValidationContext();
      }
      if (certificateValidationContext != null) {
        @SuppressWarnings("deprecation") // gRFC A29 predates match_typed_subject_alt_names
        int matchSubjectAltNamesCount = certificateValidationContext.getMatchSubjectAltNamesCount();
        if (matchSubjectAltNamesCount > 0 && server) {
          throw new ResourceInvalidException(
              "match_subject_alt_names only allowed in upstream_tls_context");
        }
        if (certificateValidationContext.getVerifyCertificateSpkiCount() > 0) {
          throw new ResourceInvalidException(
              "verify_certificate_spki in default_validation_context is not supported");
        }
        if (certificateValidationContext.getVerifyCertificateHashCount() > 0) {
          throw new ResourceInvalidException(
              "verify_certificate_hash in default_validation_context is not supported");
        }
        if (certificateValidationContext.hasRequireSignedCertificateTimestamp()) {
          throw new ResourceInvalidException(
              "require_signed_certificate_timestamp in default_validation_context is not "
                  + "supported");
        }
        if (certificateValidationContext.hasCrl()) {
          throw new ResourceInvalidException("crl in default_validation_context is not supported");
        }
        if (certificateValidationContext.hasCustomValidatorConfig()) {
          throw new ResourceInvalidException(
              "custom_validator_config in default_validation_context is not supported");
        }
      }
    }
  }

  private static String getIdentityCertInstanceName(CommonTlsContext commonTlsContext) {
    if (commonTlsContext.hasTlsCertificateProviderInstance()) {
      return commonTlsContext.getTlsCertificateProviderInstance().getInstanceName();
    }
    return null;
  }

  private static String getRootCertInstanceName(CommonTlsContext commonTlsContext) {
    if (commonTlsContext.hasValidationContext()) {
      if (commonTlsContext.getValidationContext().hasCaCertificateProviderInstance()) {
        return commonTlsContext.getValidationContext().getCaCertificateProviderInstance()
            .getInstanceName();
      }
    } else if (commonTlsContext.hasCombinedValidationContext()) {
      CommonTlsContext.CombinedCertificateValidationContext combinedCertificateValidationContext
          = commonTlsContext.getCombinedValidationContext();
      if (combinedCertificateValidationContext.hasDefaultValidationContext()
          && combinedCertificateValidationContext.getDefaultValidationContext()
          .hasCaCertificateProviderInstance()) {
        return combinedCertificateValidationContext.getDefaultValidationContext()
            .getCaCertificateProviderInstance().getInstanceName();
      }
    }
    return null;
  }

  /** xDS resource update for cluster-level configuration. */
  @AutoValue
  abstract static class CdsUpdate implements ResourceUpdate {
    abstract String clusterName();

    abstract ClusterType clusterType();

    abstract ImmutableMap<String, ?> lbPolicyConfig();

    // Only valid if lbPolicy is "ring_hash_experimental".
    abstract long minRingSize();

    // Only valid if lbPolicy is "ring_hash_experimental".
    abstract long maxRingSize();

    // Only valid if lbPolicy is "least_request_experimental".
    abstract int choiceCount();

    // Alternative resource name to be used in EDS requests.
    /// Only valid for EDS cluster.
    @Nullable
    abstract String edsServiceName();

    // Corresponding DNS name to be used if upstream endpoints of the cluster is resolvable
    // via DNS.
    // Only valid for LOGICAL_DNS cluster.
    @Nullable
    abstract String dnsHostName();

    // Load report server info for reporting loads via LRS.
    // Only valid for EDS or LOGICAL_DNS cluster.
    @Nullable
    abstract ServerInfo lrsServerInfo();

    // Max number of concurrent requests can be sent to this cluster.
    // Only valid for EDS or LOGICAL_DNS cluster.
    @Nullable
    abstract Long maxConcurrentRequests();

    // TLS context used to connect to connect to this cluster.
    // Only valid for EDS or LOGICAL_DNS cluster.
    @Nullable
    abstract UpstreamTlsContext upstreamTlsContext();

    abstract boolean isHttp11ProxyAvailable();

    // List of underlying clusters making of this aggregate cluster.
    // Only valid for AGGREGATE cluster.
    @Nullable
    abstract ImmutableList<String> prioritizedClusterNames();

    // Outlier detection configuration.
    @Nullable
    abstract OutlierDetection outlierDetection();

    abstract ImmutableMap<String, Struct> filterMetadata();

    abstract ImmutableMap<String, Object> parsedMetadata();

    private static Builder newBuilder(String clusterName) {
      return new AutoValue_XdsClusterResource_CdsUpdate.Builder()
          .clusterName(clusterName)
          .minRingSize(0)
          .maxRingSize(0)
          .choiceCount(0)
          .filterMetadata(ImmutableMap.of())
          .parsedMetadata(ImmutableMap.of())
          .isHttp11ProxyAvailable(false);
    }

    static Builder forAggregate(String clusterName, List<String> prioritizedClusterNames) {
      checkNotNull(prioritizedClusterNames, "prioritizedClusterNames");
      return newBuilder(clusterName)
          .clusterType(ClusterType.AGGREGATE)
          .prioritizedClusterNames(ImmutableList.copyOf(prioritizedClusterNames));
    }

    static Builder forEds(String clusterName, @Nullable String edsServiceName,
                          @Nullable ServerInfo lrsServerInfo, @Nullable Long maxConcurrentRequests,
                          @Nullable UpstreamTlsContext upstreamTlsContext,
                          @Nullable OutlierDetection outlierDetection,
                          boolean isHttp11ProxyAvailable) {
      return newBuilder(clusterName)
          .clusterType(ClusterType.EDS)
          .edsServiceName(edsServiceName)
          .lrsServerInfo(lrsServerInfo)
          .maxConcurrentRequests(maxConcurrentRequests)
          .upstreamTlsContext(upstreamTlsContext)
          .outlierDetection(outlierDetection)
          .isHttp11ProxyAvailable(isHttp11ProxyAvailable);
    }

    static Builder forLogicalDns(String clusterName, String dnsHostName,
                                 @Nullable ServerInfo lrsServerInfo,
                                 @Nullable Long maxConcurrentRequests,
                                 @Nullable UpstreamTlsContext upstreamTlsContext,
                                 boolean isHttp11ProxyAvailable) {
      return newBuilder(clusterName)
          .clusterType(ClusterType.LOGICAL_DNS)
          .dnsHostName(dnsHostName)
          .lrsServerInfo(lrsServerInfo)
          .maxConcurrentRequests(maxConcurrentRequests)
          .upstreamTlsContext(upstreamTlsContext)
          .isHttp11ProxyAvailable(isHttp11ProxyAvailable);
    }

    enum ClusterType {
      EDS, LOGICAL_DNS, AGGREGATE
    }

    enum LbPolicy {
      ROUND_ROBIN, RING_HASH, LEAST_REQUEST
    }

    // FIXME(chengyuanzhang): delete this after UpstreamTlsContext's toString() is fixed.
    @Override
    public final String toString() {
      return MoreObjects.toStringHelper(this)
          .add("clusterName", clusterName())
          .add("clusterType", clusterType())
          .add("lbPolicyConfig", lbPolicyConfig())
          .add("minRingSize", minRingSize())
          .add("maxRingSize", maxRingSize())
          .add("choiceCount", choiceCount())
          .add("edsServiceName", edsServiceName())
          .add("dnsHostName", dnsHostName())
          .add("lrsServerInfo", lrsServerInfo())
          .add("maxConcurrentRequests", maxConcurrentRequests())
          // Exclude upstreamTlsContext and outlierDetection as their string representations are
          // cumbersome.
          .add("prioritizedClusterNames", prioritizedClusterNames())
          .toString();
    }

    @AutoValue.Builder
    abstract static class Builder {
      // Private, use one of the static factory methods instead.
      protected abstract Builder clusterName(String clusterName);

      // Private, use one of the static factory methods instead.
      protected abstract Builder clusterType(ClusterType clusterType);

      protected abstract Builder lbPolicyConfig(ImmutableMap<String, ?> lbPolicyConfig);

      Builder roundRobinLbPolicy() {
        return this.lbPolicyConfig(ImmutableMap.of("round_robin", ImmutableMap.of()));
      }

      Builder ringHashLbPolicy(Long minRingSize, Long maxRingSize) {
        return this.lbPolicyConfig(ImmutableMap.of("ring_hash_experimental",
            ImmutableMap.of("minRingSize", minRingSize.doubleValue(), "maxRingSize",
                maxRingSize.doubleValue())));
      }

      Builder leastRequestLbPolicy(Integer choiceCount) {
        return this.lbPolicyConfig(ImmutableMap.of("least_request_experimental",
            ImmutableMap.of("choiceCount", choiceCount.doubleValue())));
      }

      // Private, use leastRequestLbPolicy(int).
      protected abstract Builder choiceCount(int choiceCount);

      // Private, use ringHashLbPolicy(long, long).
      protected abstract Builder minRingSize(long minRingSize);

      // Private, use ringHashLbPolicy(long, long).
      protected abstract Builder maxRingSize(long maxRingSize);

      // Private, use CdsUpdate.forEds() instead.
      protected abstract Builder edsServiceName(String edsServiceName);

      // Private, use CdsUpdate.forLogicalDns() instead.
      protected abstract Builder dnsHostName(String dnsHostName);

      // Private, use one of the static factory methods instead.
      protected abstract Builder lrsServerInfo(ServerInfo lrsServerInfo);

      // Private, use one of the static factory methods instead.
      protected abstract Builder maxConcurrentRequests(Long maxConcurrentRequests);

      protected abstract Builder isHttp11ProxyAvailable(boolean isHttp11ProxyAvailable);

      // Private, use one of the static factory methods instead.
      protected abstract Builder upstreamTlsContext(UpstreamTlsContext upstreamTlsContext);

      // Private, use CdsUpdate.forAggregate() instead.
      protected abstract Builder prioritizedClusterNames(List<String> prioritizedClusterNames);

      protected abstract Builder outlierDetection(OutlierDetection outlierDetection);

      protected abstract Builder filterMetadata(ImmutableMap<String, Struct> filterMetadata);

      protected abstract Builder parsedMetadata(ImmutableMap<String, Object> parsedMetadata);

      abstract CdsUpdate build();
    }
  }
}
