/*
 * Copyright 2020 The gRPC Authors
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Struct;
import io.grpc.Attributes;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.Metadata;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.internal.ForwardingClientStreamTracer;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.services.MetricReport;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.ForwardingSubchannel;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.ClusterImplLoadBalancerProvider.ClusterImplConfig;
import io.grpc.xds.Endpoints.DropOverload;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import io.grpc.xds.XdsNameResolverProvider.CallCounterProvider;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.LoadStatsManager2.ClusterDropStats;
import io.grpc.xds.client.LoadStatsManager2.ClusterLocalityStats;
import io.grpc.xds.client.Locality;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsLogger.XdsLogLevel;
import io.grpc.xds.internal.security.SecurityProtocolNegotiators;
import io.grpc.xds.internal.security.SslContextProviderSupplier;
import io.grpc.xds.orca.OrcaPerRequestUtil;
import io.grpc.xds.orca.OrcaPerRequestUtil.OrcaPerRequestReportListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Load balancer for cluster_impl_experimental LB policy. This LB policy is the child LB policy of
 * the priority_experimental LB policy and the parent LB policy of the weighted_target_experimental
 * LB policy in the xDS load balancing hierarchy. This LB policy applies cluster-level
 * configurations to requests sent to the corresponding cluster, such as drop policies, circuit
 * breakers.
 */
final class ClusterImplLoadBalancer extends LoadBalancer {

  @VisibleForTesting
  static final long DEFAULT_PER_CLUSTER_MAX_CONCURRENT_REQUESTS = 1024L;
  @VisibleForTesting
  static boolean enableCircuitBreaking =
      Strings.isNullOrEmpty(System.getenv("GRPC_XDS_EXPERIMENTAL_CIRCUIT_BREAKING"))
          || Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_CIRCUIT_BREAKING"));

  private static final Attributes.Key<AtomicReference<ClusterLocality>> ATTR_CLUSTER_LOCALITY =
      Attributes.Key.create("io.grpc.xds.ClusterImplLoadBalancer.clusterLocality");

  private final XdsLogger logger;
  private final Helper helper;
  private final ThreadSafeRandom random;
  // The following fields are effectively final.
  private String cluster;
  @Nullable
  private String edsServiceName;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private CallCounterProvider callCounterProvider;
  private ClusterDropStats dropStats;
  private ClusterImplLbHelper childLbHelper;
  private GracefulSwitchLoadBalancer childSwitchLb;

  ClusterImplLoadBalancer(Helper helper) {
    this(helper, ThreadSafeRandomImpl.instance);
  }

  ClusterImplLoadBalancer(Helper helper, ThreadSafeRandom random) {
    this.helper = checkNotNull(helper, "helper");
    this.random = checkNotNull(random, "random");
    InternalLogId logId = InternalLogId.allocate("cluster-impl-lb", helper.getAuthority());
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    Attributes attributes = resolvedAddresses.getAttributes();
    if (xdsClientPool == null) {
      xdsClientPool = attributes.get(XdsAttributes.XDS_CLIENT_POOL);
      assert xdsClientPool != null;
      xdsClient = xdsClientPool.getObject();
    }
    if (callCounterProvider == null) {
      callCounterProvider = attributes.get(XdsAttributes.CALL_COUNTER_PROVIDER);
    }

    ClusterImplConfig config =
        (ClusterImplConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (config == null) {
      return Status.INTERNAL.withDescription("No cluster configuration found");
    }

    if (cluster == null) {
      cluster = config.cluster;
      edsServiceName = config.edsServiceName;
      childLbHelper = new ClusterImplLbHelper(
          callCounterProvider.getOrCreate(config.cluster, config.edsServiceName),
          config.lrsServerInfo);
      childSwitchLb = new GracefulSwitchLoadBalancer(childLbHelper);
      // Assume load report server does not change throughout cluster lifetime.
      if (config.lrsServerInfo != null) {
        dropStats = xdsClient.addClusterDropStats(config.lrsServerInfo, cluster, edsServiceName);
      }
    }

    childLbHelper.updateDropPolicies(config.dropCategories);
    childLbHelper.updateMaxConcurrentRequests(config.maxConcurrentRequests);
    childLbHelper.updateSslContextProviderSupplier(config.tlsContext);
    childLbHelper.updateFilterMetadata(config.filterMetadata);

    childSwitchLb.handleResolvedAddresses(
        resolvedAddresses.toBuilder()
            .setAttributes(attributes.toBuilder()
              .set(NameResolver.ATTR_BACKEND_SERVICE, cluster)
              .build())
            .setLoadBalancingPolicyConfig(config.childConfig)
            .build());
    return Status.OK;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (childSwitchLb != null) {
      childSwitchLb.handleNameResolutionError(error);
    } else {
      helper.updateBalancingState(
          ConnectivityState.TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
    }
  }

  @Override
  public void requestConnection() {
    if (childSwitchLb != null) {
      childSwitchLb.requestConnection();
    }
  }

  @Override
  public void shutdown() {
    if (dropStats != null) {
      dropStats.release();
    }
    if (childSwitchLb != null) {
      childSwitchLb.shutdown();
      if (childLbHelper != null) {
        childLbHelper.updateSslContextProviderSupplier(null);
        childLbHelper = null;
      }
    }
    if (xdsClient != null) {
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
  }

  /**
   * A decorated {@link LoadBalancer.Helper} that applies configurations for connections
   * or requests to endpoints in the cluster.
   */
  private final class ClusterImplLbHelper extends ForwardingLoadBalancerHelper {
    private final AtomicLong inFlights;
    private ConnectivityState currentState = ConnectivityState.IDLE;
    private SubchannelPicker currentPicker = new FixedResultPicker(PickResult.withNoResult());
    private List<DropOverload> dropPolicies = Collections.emptyList();
    private long maxConcurrentRequests = DEFAULT_PER_CLUSTER_MAX_CONCURRENT_REQUESTS;
    @Nullable
    private SslContextProviderSupplier sslContextProviderSupplier;
    private Map<String, Struct> filterMetadata = ImmutableMap.of();
    @Nullable
    private final ServerInfo lrsServerInfo;

    private ClusterImplLbHelper(AtomicLong inFlights, @Nullable ServerInfo lrsServerInfo) {
      this.inFlights = checkNotNull(inFlights, "inFlights");
      this.lrsServerInfo = lrsServerInfo;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      currentState = newState;
      currentPicker =  newPicker;
      SubchannelPicker picker = new RequestLimitingSubchannelPicker(
          newPicker, dropPolicies, maxConcurrentRequests, filterMetadata);
      delegate().updateBalancingState(newState, picker);
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      List<EquivalentAddressGroup> addresses = withAdditionalAttributes(args.getAddresses());
      // This value for  ClusterLocality is not recommended for general use.
      // Currently, we extract locality data from the first address, even before the subchannel is
      // READY.
      // This is mainly to accommodate scenarios where a Load Balancing API (like "pick first")
      // might return the subchannel before it is READY. Typically, we wouldn't report load for such
      // selections because the channel will disregard the chosen (not-ready) subchannel.
      // However, we needed to ensure this case is handled.
      ClusterLocality clusterLocality = createClusterLocalityFromAttributes(
          args.getAddresses().get(0).getAttributes());
      AtomicReference<ClusterLocality> localityAtomicReference = new AtomicReference<>(
          clusterLocality);
      Attributes.Builder attrsBuilder = args.getAttributes().toBuilder()
          .set(ATTR_CLUSTER_LOCALITY, localityAtomicReference);
      if (GrpcUtil.getFlag("GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE", false)) {
        String hostname = args.getAddresses().get(0).getAttributes()
            .get(XdsAttributes.ATTR_ADDRESS_NAME);
        if (hostname != null) {
          attrsBuilder.set(XdsAttributes.ATTR_ADDRESS_NAME, hostname);
        }
      }
      args = args.toBuilder().setAddresses(addresses).setAttributes(attrsBuilder.build()).build();
      final Subchannel subchannel = delegate().createSubchannel(args);

      return new ForwardingSubchannel() {
        @Override
        public void start(SubchannelStateListener listener) {
          delegate().start(new SubchannelStateListener() {
            @Override
            public void onSubchannelState(ConnectivityStateInfo newState) {
              // Do nothing if LB has been shutdown
              if (xdsClient != null && newState.getState().equals(ConnectivityState.READY)) {
                // Get locality based on the connected address attributes
                ClusterLocality updatedClusterLocality = createClusterLocalityFromAttributes(
                    subchannel.getConnectedAddressAttributes());
                ClusterLocality oldClusterLocality = localityAtomicReference
                    .getAndSet(updatedClusterLocality);
                oldClusterLocality.release();
              }
              listener.onSubchannelState(newState);
            }
          });
        }

        @Override
        public void shutdown() {
          localityAtomicReference.get().release();
          delegate().shutdown();
        }

        @Override
        public void updateAddresses(List<EquivalentAddressGroup> addresses) {
          delegate().updateAddresses(withAdditionalAttributes(addresses));
        }

        @Override
        protected Subchannel delegate() {
          return subchannel;
        }
      };
    }

    private List<EquivalentAddressGroup> withAdditionalAttributes(
        List<EquivalentAddressGroup> addresses) {
      List<EquivalentAddressGroup> newAddresses = new ArrayList<>();
      for (EquivalentAddressGroup eag : addresses) {
        Attributes.Builder attrBuilder = eag.getAttributes().toBuilder().set(
            XdsAttributes.ATTR_CLUSTER_NAME, cluster);
        if (sslContextProviderSupplier != null) {
          attrBuilder.set(
              SecurityProtocolNegotiators.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER,
              sslContextProviderSupplier);
        }
        newAddresses.add(new EquivalentAddressGroup(eag.getAddresses(), attrBuilder.build()));
      }
      return newAddresses;
    }

    private ClusterLocality createClusterLocalityFromAttributes(Attributes addressAttributes) {
      Locality locality = addressAttributes.get(XdsAttributes.ATTR_LOCALITY);
      String localityName = addressAttributes.get(XdsAttributes.ATTR_LOCALITY_NAME);

      // Endpoint addresses resolved by ClusterResolverLoadBalancer should always contain
      // attributes with its locality, including endpoints in LOGICAL_DNS clusters.
      // In case of not (which really shouldn't), loads are aggregated under an empty
      // locality.
      if (locality == null) {
        locality = Locality.create("", "", "");
        localityName = "";
      }

      final ClusterLocalityStats localityStats =
          (lrsServerInfo == null)
              ? null
              : xdsClient.addClusterLocalityStats(lrsServerInfo, cluster,
                  edsServiceName, locality);

      return new ClusterLocality(localityStats, localityName);
    }

    @Override
    protected Helper delegate()  {
      return helper;
    }

    private void updateDropPolicies(List<DropOverload> dropOverloads) {
      if (!dropPolicies.equals(dropOverloads)) {
        dropPolicies = dropOverloads;
        updateBalancingState(currentState, currentPicker);
      }
    }

    private void updateMaxConcurrentRequests(@Nullable Long maxConcurrentRequests) {
      if (Objects.equals(this.maxConcurrentRequests, maxConcurrentRequests)) {
        return;
      }
      this.maxConcurrentRequests =
          maxConcurrentRequests != null
              ? maxConcurrentRequests
              : DEFAULT_PER_CLUSTER_MAX_CONCURRENT_REQUESTS;
      updateBalancingState(currentState, currentPicker);
    }

    private void updateSslContextProviderSupplier(@Nullable UpstreamTlsContext tlsContext) {
      UpstreamTlsContext currentTlsContext =
          sslContextProviderSupplier != null
              ? (UpstreamTlsContext)sslContextProviderSupplier.getTlsContext()
              : null;
      if (Objects.equals(currentTlsContext,  tlsContext)) {
        return;
      }
      if (sslContextProviderSupplier != null) {
        sslContextProviderSupplier.close();
      }
      sslContextProviderSupplier =
          tlsContext != null
              ? new SslContextProviderSupplier(tlsContext,
                                               (TlsContextManager) xdsClient.getSecurityConfig())
              : null;
    }

    private void updateFilterMetadata(Map<String, Struct> filterMetadata) {
      this.filterMetadata = ImmutableMap.copyOf(filterMetadata);
    }

    private class RequestLimitingSubchannelPicker extends SubchannelPicker {
      private final SubchannelPicker delegate;
      private final List<DropOverload> dropPolicies;
      private final long maxConcurrentRequests;
      private final Map<String, Struct> filterMetadata;

      private RequestLimitingSubchannelPicker(SubchannelPicker delegate,
          List<DropOverload> dropPolicies, long maxConcurrentRequests,
          Map<String, Struct> filterMetadata) {
        this.delegate = delegate;
        this.dropPolicies = dropPolicies;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.filterMetadata = checkNotNull(filterMetadata, "filterMetadata");
      }

      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        args.getCallOptions().getOption(ClusterImplLoadBalancerProvider.FILTER_METADATA_CONSUMER)
            .accept(filterMetadata);
        args.getPickDetailsConsumer().addOptionalLabel("grpc.lb.backend_service", cluster);
        for (DropOverload dropOverload : dropPolicies) {
          int rand = random.nextInt(1_000_000);
          if (rand < dropOverload.dropsPerMillion()) {
            logger.log(XdsLogLevel.INFO, "Drop request with category: {0}",
                dropOverload.category());
            if (dropStats != null) {
              dropStats.recordDroppedRequest(dropOverload.category());
            }
            return PickResult.withDrop(
                Status.UNAVAILABLE.withDescription("Dropped: " + dropOverload.category()));
          }
        }
        PickResult result = delegate.pickSubchannel(args);
        if (result.getStatus().isOk() && result.getSubchannel() != null) {
          if (enableCircuitBreaking) {
            if (inFlights.get() >= maxConcurrentRequests) {
              if (dropStats != null) {
                dropStats.recordDroppedRequest();
              }
              return PickResult.withDrop(Status.UNAVAILABLE.withDescription(
                  String.format(Locale.US, "Cluster max concurrent requests limit of %d exceeded",
                      maxConcurrentRequests)));
            }
          }
          final AtomicReference<ClusterLocality> clusterLocality =
              result.getSubchannel().getAttributes().get(ATTR_CLUSTER_LOCALITY);

          if (clusterLocality != null) {
            ClusterLocalityStats stats = clusterLocality.get().getClusterLocalityStats();
            if (stats != null) {
              String localityName =
                  result.getSubchannel().getAttributes().get(ATTR_CLUSTER_LOCALITY).get()
                      .getClusterLocalityName();
              args.getPickDetailsConsumer().addOptionalLabel("grpc.lb.locality", localityName);

              ClientStreamTracer.Factory tracerFactory = new CountingStreamTracerFactory(
                  stats, inFlights, result.getStreamTracerFactory());
              ClientStreamTracer.Factory orcaTracerFactory = OrcaPerRequestUtil.getInstance()
                  .newOrcaClientStreamTracerFactory(tracerFactory, new OrcaPerRpcListener(stats));
              result = PickResult.withSubchannel(result.getSubchannel(),
                  orcaTracerFactory);
            }
          }
          if (args.getCallOptions().getOption(XdsNameResolver.AUTO_HOST_REWRITE_KEY) != null
              && args.getCallOptions().getOption(XdsNameResolver.AUTO_HOST_REWRITE_KEY)) {
            result = PickResult.withSubchannel(result.getSubchannel(),
                result.getStreamTracerFactory(),
                result.getSubchannel().getAttributes().get(
                    XdsAttributes.ATTR_ADDRESS_NAME));
          }
        }
        return result;
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this).add("delegate", delegate).toString();
      }
    }
  }

  private static final class CountingStreamTracerFactory extends
      ClientStreamTracer.Factory {
    private final ClusterLocalityStats stats;
    private final AtomicLong inFlights;
    @Nullable
    private final ClientStreamTracer.Factory delegate;

    private CountingStreamTracerFactory(
        ClusterLocalityStats stats, AtomicLong inFlights,
        @Nullable ClientStreamTracer.Factory delegate) {
      this.stats = checkNotNull(stats, "stats");
      this.inFlights = checkNotNull(inFlights, "inFlights");
      this.delegate = delegate;
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
      stats.recordCallStarted();
      inFlights.incrementAndGet();
      if (delegate == null) {
        return new ClientStreamTracer() {
          @Override
          public void streamClosed(Status status) {
            stats.recordCallFinished(status);
            inFlights.decrementAndGet();
          }
        };
      }
      final ClientStreamTracer delegatedTracer = delegate.newClientStreamTracer(info, headers);
      return new ForwardingClientStreamTracer() {
        @Override
        protected ClientStreamTracer delegate() {
          return delegatedTracer;
        }

        @Override
        public void streamClosed(Status status) {
          stats.recordCallFinished(status);
          inFlights.decrementAndGet();
          delegate().streamClosed(status);
        }
      };
    }
  }

  private static final class OrcaPerRpcListener implements OrcaPerRequestReportListener {

    private final ClusterLocalityStats stats;

    private OrcaPerRpcListener(ClusterLocalityStats stats) {
      this.stats = checkNotNull(stats, "stats");
    }

    /**
     * Copies {@link MetricReport#getNamedMetrics()} to {@link ClusterLocalityStats} such that it is
     * included in the snapshot for the LRS report sent to the LRS server.
     */
    @Override
    public void onLoadReport(MetricReport report) {
      stats.recordBackendLoadMetricStats(report.getNamedMetrics());
    }
  }

  /**
   * Represents the {@link ClusterLocalityStats} and network locality name of a cluster.
   */
  static final class ClusterLocality {
    private final ClusterLocalityStats clusterLocalityStats;
    private final String clusterLocalityName;

    @VisibleForTesting
    ClusterLocality(ClusterLocalityStats localityStats, String localityName) {
      this.clusterLocalityStats = localityStats;
      this.clusterLocalityName = localityName;
    }

    ClusterLocalityStats getClusterLocalityStats() {
      return clusterLocalityStats;
    }

    String getClusterLocalityName() {
      return clusterLocalityName;
    }

    @VisibleForTesting
    void release() {
      if (clusterLocalityStats != null) {
        clusterLocalityStats.release();
      }
    }
  }
}
