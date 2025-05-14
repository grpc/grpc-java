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
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.XdsLbPolicies.PRIORITY_POLICY_NAME;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Struct;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.HttpConnectProxiedSocketAddress;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.ObjectPool;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionLoadBalancerConfig;
import io.grpc.xds.ClusterImplLoadBalancerProvider.ClusterImplConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig.DiscoveryMechanism;
import io.grpc.xds.Endpoints.DropOverload;
import io.grpc.xds.Endpoints.LbEndpoint;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.EnvoyServerProtoData.FailurePercentageEjection;
import io.grpc.xds.EnvoyServerProtoData.OutlierDetection;
import io.grpc.xds.EnvoyServerProtoData.SuccessRateEjection;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig;
import io.grpc.xds.XdsEndpointResource.EdsUpdate;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.Locality;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClient.ResourceWatcher;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsLogger.XdsLogLevel;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Load balancer for cluster_resolver_experimental LB policy. This LB policy is the child LB policy
 * of the cds_experimental LB policy and the parent LB policy of the priority_experimental LB
 * policy in the xDS load balancing hierarchy. This policy resolves endpoints of non-aggregate
 * clusters (e.g., EDS or Logical DNS) and groups endpoints in priorities and localities to be
 * used in the downstream LB policies for fine-grained load balancing purposes.
 */
final class ClusterResolverLoadBalancer extends LoadBalancer {
  // DNS-resolved endpoints do not have the definition of the locality it belongs to, just hardcode
  // to an empty locality.
  private static final Locality LOGICAL_DNS_CLUSTER_LOCALITY = Locality.create("", "", "");
  private final XdsLogger logger;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final LoadBalancerRegistry lbRegistry;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final GracefulSwitchLoadBalancer delegate;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private ClusterResolverConfig config;

  ClusterResolverLoadBalancer(Helper helper) {
    this(helper, LoadBalancerRegistry.getDefaultRegistry(),
        new ExponentialBackoffPolicy.Provider());
  }

  @VisibleForTesting
  ClusterResolverLoadBalancer(Helper helper, LoadBalancerRegistry lbRegistry,
      BackoffPolicy.Provider backoffPolicyProvider) {
    this.lbRegistry = checkNotNull(lbRegistry, "lbRegistry");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    delegate = new GracefulSwitchLoadBalancer(helper);
    logger = XdsLogger.withLogId(
        InternalLogId.allocate("cluster-resolver-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    if (xdsClientPool == null) {
      xdsClientPool = resolvedAddresses.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL);
      xdsClient = xdsClientPool.getObject();
    }
    ClusterResolverConfig config =
        (ClusterResolverConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (!Objects.equals(this.config, config)) {
      logger.log(XdsLogLevel.DEBUG, "Config: {0}", config);
      this.config = config;
      Object gracefulConfig = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
          new ClusterResolverLbStateFactory(), config);
      delegate.handleResolvedAddresses(
          resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(gracefulConfig).build());
    }
    return Status.OK;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    delegate.handleNameResolutionError(error);
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    delegate.shutdown();
    if (xdsClientPool != null) {
      xdsClientPool.returnObject(xdsClient);
    }
  }

  private final class ClusterResolverLbStateFactory extends LoadBalancer.Factory {
    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return new ClusterResolverLbState(helper);
    }
  }

  /**
   * The state of a cluster_resolver LB working session. A new instance is created whenever
   * the cluster_resolver LB receives a new config. The old instance is replaced when the
   * new one is ready to handle new RPCs.
   */
  private final class ClusterResolverLbState extends LoadBalancer {
    private final Helper helper;
    private final List<String> clusters = new ArrayList<>();
    private final Map<String, ClusterState> clusterStates = new HashMap<>();
    private Object endpointLbConfig;
    private ResolvedAddresses resolvedAddresses;
    private LoadBalancer childLb;

    ClusterResolverLbState(Helper helper) {
      this.helper = new RefreshableHelper(checkNotNull(helper, "helper"));
      logger.log(XdsLogLevel.DEBUG, "New ClusterResolverLbState");
    }

    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      this.resolvedAddresses = resolvedAddresses;
      ClusterResolverConfig config =
          (ClusterResolverConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
      endpointLbConfig = config.lbConfig;
      for (DiscoveryMechanism instance : config.discoveryMechanisms) {
        clusters.add(instance.cluster);
        ClusterState state;
        if (instance.type == DiscoveryMechanism.Type.EDS) {
          state = new EdsClusterState(instance.cluster, instance.edsServiceName,
              instance.lrsServerInfo, instance.maxConcurrentRequests, instance.tlsContext,
              instance.filterMetadata, instance.outlierDetection);
        } else {  // logical DNS
          state = new LogicalDnsClusterState(instance.cluster, instance.dnsHostName,
              instance.lrsServerInfo, instance.maxConcurrentRequests, instance.tlsContext,
              instance.filterMetadata);
        }
        clusterStates.put(instance.cluster, state);
        state.start();
      }
      return Status.OK;
    }

    @Override
    public void handleNameResolutionError(Status error) {
      if (childLb != null) {
        childLb.handleNameResolutionError(error);
      } else {
        helper.updateBalancingState(
            TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
      }
    }

    @Override
    public void shutdown() {
      for (ClusterState state : clusterStates.values()) {
        state.shutdown();
      }
      if (childLb != null) {
        childLb.shutdown();
      }
    }

    private void handleEndpointResourceUpdate() {
      List<EquivalentAddressGroup> addresses = new ArrayList<>();
      Map<String, PriorityChildConfig> priorityChildConfigs = new HashMap<>();
      List<String> priorities = new ArrayList<>();  // totally ordered priority list

      Status endpointNotFound = Status.OK;
      for (String cluster : clusters) {
        ClusterState state = clusterStates.get(cluster);
        // Propagate endpoints to the child LB policy only after all clusters have been resolved.
        if (!state.resolved && state.status.isOk()) {
          return;
        }
        if (state.result != null) {
          addresses.addAll(state.result.addresses);
          priorityChildConfigs.putAll(state.result.priorityChildConfigs);
          priorities.addAll(state.result.priorities);
        } else {
          endpointNotFound = state.status;
        }
      }
      if (addresses.isEmpty()) {
        if (endpointNotFound.isOk()) {
          endpointNotFound = Status.UNAVAILABLE.withDescription(
              "No usable endpoint from cluster(s): " + clusters);
        } else {
          endpointNotFound =
              Status.UNAVAILABLE.withCause(endpointNotFound.getCause())
                  .withDescription(endpointNotFound.getDescription());
        }
        helper.updateBalancingState(
            TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(endpointNotFound)));
        if (childLb != null) {
          childLb.shutdown();
          childLb = null;
        }
        return;
      }
      PriorityLbConfig childConfig =
          new PriorityLbConfig(Collections.unmodifiableMap(priorityChildConfigs),
              Collections.unmodifiableList(priorities));
      if (childLb == null) {
        childLb = lbRegistry.getProvider(PRIORITY_POLICY_NAME).newLoadBalancer(helper);
      }
      childLb.handleResolvedAddresses(
          resolvedAddresses.toBuilder()
              .setLoadBalancingPolicyConfig(childConfig)
              .setAddresses(Collections.unmodifiableList(addresses))
              .build());
    }

    private void handleEndpointResolutionError() {
      boolean allInError = true;
      Status error = null;
      for (String cluster : clusters) {
        ClusterState state = clusterStates.get(cluster);
        if (state.status.isOk()) {
          allInError = false;
        } else {
          error = state.status;
        }
      }
      if (allInError) {
        if (childLb != null) {
          childLb.handleNameResolutionError(error);
        } else {
          helper.updateBalancingState(
              TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
        }
      }
    }

    /**
     * Wires re-resolution requests from downstream LB policies with DNS resolver.
     */
    private final class RefreshableHelper extends ForwardingLoadBalancerHelper {
      private final Helper delegate;

      private RefreshableHelper(Helper delegate) {
        this.delegate = checkNotNull(delegate, "delegate");
      }

      @Override
      public void refreshNameResolution() {
        for (ClusterState state : clusterStates.values()) {
          if (state instanceof LogicalDnsClusterState) {
            ((LogicalDnsClusterState) state).refresh();
          }
        }
      }

      @Override
      protected Helper delegate() {
        return delegate;
      }
    }

    /**
     * Resolution state of an underlying cluster.
     */
    private abstract class ClusterState {
      // Name of the cluster to be resolved.
      protected final String name;
      @Nullable
      protected final ServerInfo lrsServerInfo;
      @Nullable
      protected final Long maxConcurrentRequests;
      @Nullable
      protected final UpstreamTlsContext tlsContext;
      protected final Map<String, Struct> filterMetadata;
      @Nullable
      protected final OutlierDetection outlierDetection;
      // Resolution status, may contain most recent error encountered.
      protected Status status = Status.OK;
      // True if has received resolution result.
      protected boolean resolved;
      // Most recently resolved addresses and config, or null if resource not exists.
      @Nullable
      protected ClusterResolutionResult result;

      protected boolean shutdown;

      private ClusterState(String name, @Nullable ServerInfo lrsServerInfo,
          @Nullable Long maxConcurrentRequests, @Nullable UpstreamTlsContext tlsContext,
          Map<String, Struct> filterMetadata, @Nullable OutlierDetection outlierDetection) {
        this.name = name;
        this.lrsServerInfo = lrsServerInfo;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.tlsContext = tlsContext;
        this.filterMetadata = ImmutableMap.copyOf(filterMetadata);
        this.outlierDetection = outlierDetection;
      }

      abstract void start();

      void shutdown() {
        shutdown = true;
      }
    }

    private final class EdsClusterState extends ClusterState implements ResourceWatcher<EdsUpdate> {
      @Nullable
      private final String edsServiceName;
      private Map<Locality, String> localityPriorityNames = Collections.emptyMap();
      int priorityNameGenId = 1;

      private EdsClusterState(String name, @Nullable String edsServiceName,
          @Nullable ServerInfo lrsServerInfo, @Nullable Long maxConcurrentRequests,
          @Nullable UpstreamTlsContext tlsContext, Map<String, Struct> filterMetadata,
          @Nullable OutlierDetection outlierDetection) {
        super(name, lrsServerInfo, maxConcurrentRequests, tlsContext, filterMetadata,
            outlierDetection);
        this.edsServiceName = edsServiceName;
      }

      @Override
      void start() {
        String resourceName = edsServiceName != null ? edsServiceName : name;
        logger.log(XdsLogLevel.INFO, "Start watching EDS resource {0}", resourceName);
        xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),
            resourceName, this, syncContext);
      }

      @Override
      protected void shutdown() {
        super.shutdown();
        String resourceName = edsServiceName != null ? edsServiceName : name;
        logger.log(XdsLogLevel.INFO, "Stop watching EDS resource {0}", resourceName);
        xdsClient.cancelXdsResourceWatch(XdsEndpointResource.getInstance(), resourceName, this);
      }

      @Override
      public void onChanged(final EdsUpdate update) {
        class EndpointsUpdated implements Runnable {
          @Override
          public void run() {
            if (shutdown) {
              return;
            }
            logger.log(XdsLogLevel.DEBUG, "Received endpoint update {0}", update);
            if (logger.isLoggable(XdsLogLevel.INFO)) {
              logger.log(XdsLogLevel.INFO, "Cluster {0}: {1} localities, {2} drop categories",
                  update.clusterName, update.localityLbEndpointsMap.size(),
                  update.dropPolicies.size());
            }
            Map<Locality, LocalityLbEndpoints> localityLbEndpoints =
                update.localityLbEndpointsMap;
            List<DropOverload> dropOverloads = update.dropPolicies;
            List<EquivalentAddressGroup> addresses = new ArrayList<>();
            Map<String, Map<Locality, Integer>> prioritizedLocalityWeights = new HashMap<>();
            List<String> sortedPriorityNames = generatePriorityNames(name, localityLbEndpoints);
            for (Locality locality : localityLbEndpoints.keySet()) {
              LocalityLbEndpoints localityLbInfo = localityLbEndpoints.get(locality);
              String priorityName = localityPriorityNames.get(locality);
              boolean discard = true;
              for (LbEndpoint endpoint : localityLbInfo.endpoints()) {
                if (endpoint.isHealthy()) {
                  discard = false;
                  long weight = localityLbInfo.localityWeight();
                  if (endpoint.loadBalancingWeight() != 0) {
                    weight *= endpoint.loadBalancingWeight();
                  }
                  String localityName = localityName(locality);
                  Attributes attr =
                      endpoint.eag().getAttributes().toBuilder()
                          .set(XdsAttributes.ATTR_LOCALITY, locality)
                          .set(XdsAttributes.ATTR_LOCALITY_NAME, localityName)
                          .set(XdsAttributes.ATTR_LOCALITY_WEIGHT,
                              localityLbInfo.localityWeight())
                          .set(XdsAttributes.ATTR_SERVER_WEIGHT, weight)
                          .set(XdsAttributes.ATTR_ADDRESS_NAME, endpoint.hostname())
                          .build();

                  EquivalentAddressGroup eag;
                  if (config.isHttp11ProxyAvailable()) {
                    List<SocketAddress> rewrittenAddresses = new ArrayList<>();
                    for (SocketAddress addr : endpoint.eag().getAddresses()) {
                      rewrittenAddresses.add(rewriteAddress(
                          addr, endpoint.endpointMetadata(), localityLbInfo.localityMetadata()));
                    }
                    eag = new EquivalentAddressGroup(rewrittenAddresses, attr);
                  } else {
                    eag = new EquivalentAddressGroup(endpoint.eag().getAddresses(), attr);
                  }
                  eag = AddressFilter.setPathFilter(eag, Arrays.asList(priorityName, localityName));
                  addresses.add(eag);
                }
              }
              if (discard) {
                logger.log(XdsLogLevel.INFO,
                    "Discard locality {0} with 0 healthy endpoints", locality);
                continue;
              }
              if (!prioritizedLocalityWeights.containsKey(priorityName)) {
                prioritizedLocalityWeights.put(priorityName, new HashMap<Locality, Integer>());
              }
              prioritizedLocalityWeights.get(priorityName).put(
                  locality, localityLbInfo.localityWeight());
            }
            if (prioritizedLocalityWeights.isEmpty()) {
              // Will still update the result, as if the cluster resource is revoked.
              logger.log(XdsLogLevel.INFO,
                  "Cluster {0} has no usable priority/locality/endpoint", update.clusterName);
            }
            sortedPriorityNames.retainAll(prioritizedLocalityWeights.keySet());
            Map<String, PriorityChildConfig> priorityChildConfigs =
                generateEdsBasedPriorityChildConfigs(
                    name, edsServiceName, lrsServerInfo, maxConcurrentRequests, tlsContext,
                    filterMetadata, outlierDetection, endpointLbConfig, lbRegistry,
                    prioritizedLocalityWeights, dropOverloads);
            status = Status.OK;
            resolved = true;
            result = new ClusterResolutionResult(addresses, priorityChildConfigs,
                sortedPriorityNames);
            handleEndpointResourceUpdate();
          }
        }

        new EndpointsUpdated().run();
      }

      private SocketAddress rewriteAddress(SocketAddress addr,
          ImmutableMap<String, Object> endpointMetadata,
          ImmutableMap<String, Object> localityMetadata) {
        if (!(addr instanceof InetSocketAddress)) {
          return addr;
        }

        SocketAddress proxyAddress;
        try {
          proxyAddress = (SocketAddress) endpointMetadata.get(
              "envoy.http11_proxy_transport_socket.proxy_address");
          if (proxyAddress == null) {
            proxyAddress = (SocketAddress) localityMetadata.get(
                "envoy.http11_proxy_transport_socket.proxy_address");
          }
        } catch (ClassCastException e) {
          return addr;
        }

        if (proxyAddress == null) {
          return addr;
        }

        return HttpConnectProxiedSocketAddress.newBuilder()
            .setTargetAddress((InetSocketAddress) addr)
            .setProxyAddress(proxyAddress)
            .build();
      }

      private List<String> generatePriorityNames(String name,
          Map<Locality, LocalityLbEndpoints> localityLbEndpoints) {
        TreeMap<Integer, List<Locality>> todo = new TreeMap<>();
        for (Locality locality : localityLbEndpoints.keySet()) {
          int priority = localityLbEndpoints.get(locality).priority();
          if (!todo.containsKey(priority)) {
            todo.put(priority, new ArrayList<>());
          }
          todo.get(priority).add(locality);
        }
        Map<Locality, String> newNames = new HashMap<>();
        Set<String> usedNames = new HashSet<>();
        List<String> ret = new ArrayList<>();
        for (Integer priority: todo.keySet()) {
          String foundName = "";
          for (Locality locality : todo.get(priority)) {
            if (localityPriorityNames.containsKey(locality)
                && usedNames.add(localityPriorityNames.get(locality))) {
              foundName = localityPriorityNames.get(locality);
              break;
            }
          }
          if ("".equals(foundName)) {
            foundName = String.format(Locale.US, "%s[child%d]", name, priorityNameGenId++);
          }
          for (Locality locality : todo.get(priority)) {
            newNames.put(locality, foundName);
          }
          ret.add(foundName);
        }
        localityPriorityNames = newNames;
        return ret;
      }

      @Override
      public void onResourceDoesNotExist(final String resourceName) {
        if (shutdown) {
          return;
        }
        logger.log(XdsLogLevel.INFO, "Resource {0} unavailable", resourceName);
        status = Status.OK;
        resolved = true;
        result = null;  // resource revoked
        handleEndpointResourceUpdate();
      }

      @Override
      public void onError(final Status error) {
        if (shutdown) {
          return;
        }
        String resourceName = edsServiceName != null ? edsServiceName : name;
        status = Status.UNAVAILABLE
            .withDescription(String.format("Unable to load EDS %s. xDS server returned: %s: %s",
                  resourceName, error.getCode(), error.getDescription()))
            .withCause(error.getCause());
        logger.log(XdsLogLevel.WARNING, "Received EDS error: {0}", error);
        handleEndpointResolutionError();
      }
    }

    private final class LogicalDnsClusterState extends ClusterState {
      private final String dnsHostName;
      private final NameResolver.Factory nameResolverFactory;
      private final NameResolver.Args nameResolverArgs;
      private NameResolver resolver;
      @Nullable
      private BackoffPolicy backoffPolicy;
      @Nullable
      private ScheduledHandle scheduledRefresh;

      private LogicalDnsClusterState(String name, String dnsHostName,
          @Nullable ServerInfo lrsServerInfo, @Nullable Long maxConcurrentRequests,
          @Nullable UpstreamTlsContext tlsContext, Map<String, Struct> filterMetadata) {
        super(name, lrsServerInfo, maxConcurrentRequests, tlsContext, filterMetadata, null);
        this.dnsHostName = checkNotNull(dnsHostName, "dnsHostName");
        nameResolverFactory =
            checkNotNull(helper.getNameResolverRegistry().asFactory(), "nameResolverFactory");
        nameResolverArgs = checkNotNull(helper.getNameResolverArgs(), "nameResolverArgs");
      }

      @Override
      void start() {
        URI uri;
        try {
          uri = new URI("dns", "", "/" + dnsHostName, null);
        } catch (URISyntaxException e) {
          status = Status.INTERNAL.withDescription(
              "Bug, invalid URI creation: " + dnsHostName).withCause(e);
          handleEndpointResolutionError();
          return;
        }
        resolver = nameResolverFactory.newNameResolver(uri, nameResolverArgs);
        if (resolver == null) {
          status = Status.INTERNAL.withDescription("Xds cluster resolver lb for logical DNS "
              + "cluster [" + name + "] cannot find DNS resolver with uri:" + uri);
          handleEndpointResolutionError();
          return;
        }
        resolver.start(new NameResolverListener(dnsHostName));
      }

      void refresh() {
        if (resolver == null) {
          return;
        }
        cancelBackoff();
        resolver.refresh();
      }

      @Override
      void shutdown() {
        super.shutdown();
        if (resolver != null) {
          resolver.shutdown();
        }
        cancelBackoff();
      }

      private void cancelBackoff() {
        if (scheduledRefresh != null) {
          scheduledRefresh.cancel();
          scheduledRefresh = null;
          backoffPolicy = null;
        }
      }

      private class DelayedNameResolverRefresh implements Runnable {
        @Override
        public void run() {
          scheduledRefresh = null;
          if (!shutdown) {
            resolver.refresh();
          }
        }
      }

      private class NameResolverListener extends NameResolver.Listener2 {
        private final String dnsHostName;

        NameResolverListener(String dnsHostName) {
          this.dnsHostName = dnsHostName;
        }

        @Override
        public void onResult(final ResolutionResult resolutionResult) {
          syncContext.execute(() -> onResult2(resolutionResult));
        }

        @Override
        public Status onResult2(final ResolutionResult resolutionResult) {
          if (shutdown) {
            return Status.OK;
          }
          // Arbitrary priority notation for all DNS-resolved endpoints.
          String priorityName = priorityName(name, 0);  // value doesn't matter
          List<EquivalentAddressGroup> addresses = new ArrayList<>();
          StatusOr<List<EquivalentAddressGroup>> addressesOrError =
                  resolutionResult.getAddressesOrError();
          if (addressesOrError.hasValue()) {
            backoffPolicy = null;  // reset backoff sequence if succeeded
            for (EquivalentAddressGroup eag : addressesOrError.getValue()) {
              // No weight attribute is attached, all endpoint-level LB policy should be able
              // to handle such it.
              String localityName = localityName(LOGICAL_DNS_CLUSTER_LOCALITY);
              Attributes attr = eag.getAttributes().toBuilder()
                      .set(XdsAttributes.ATTR_LOCALITY, LOGICAL_DNS_CLUSTER_LOCALITY)
                      .set(XdsAttributes.ATTR_LOCALITY_NAME, localityName)
                      .set(XdsAttributes.ATTR_ADDRESS_NAME, dnsHostName)
                      .build();
              eag = new EquivalentAddressGroup(eag.getAddresses(), attr);
              eag = AddressFilter.setPathFilter(eag, Arrays.asList(priorityName, localityName));
              addresses.add(eag);
            }
            PriorityChildConfig priorityChildConfig = generateDnsBasedPriorityChildConfig(
                    name, lrsServerInfo, maxConcurrentRequests, tlsContext, filterMetadata,
                    lbRegistry, Collections.<DropOverload>emptyList());
            status = Status.OK;
            resolved = true;
            result = new ClusterResolutionResult(addresses, priorityName, priorityChildConfig);
            handleEndpointResourceUpdate();
            return Status.OK;
          } else {
            handleErrorInSyncContext(addressesOrError.getStatus());
            return addressesOrError.getStatus();
          }
        }

        @Override
        public void onError(final Status error) {
          syncContext.execute(() -> handleErrorInSyncContext(error));
        }

        private void handleErrorInSyncContext(final Status error) {
          if (shutdown) {
            return;
          }
          status = error;
          // NameResolver.Listener API cannot distinguish between address-not-found and
          // transient errors. If the error occurs in the first resolution, treat it as
          // address not found. Otherwise, either there is previously resolved addresses
          // previously encountered error, propagate the error to downstream/upstream and
          // let downstream/upstream handle it.
          if (!resolved) {
            resolved = true;
            handleEndpointResourceUpdate();
          } else {
            handleEndpointResolutionError();
          }
          if (scheduledRefresh != null && scheduledRefresh.isPending()) {
            return;
          }
          if (backoffPolicy == null) {
            backoffPolicy = backoffPolicyProvider.get();
          }
          long delayNanos = backoffPolicy.nextBackoffNanos();
          logger.log(XdsLogLevel.DEBUG,
                  "Logical DNS resolver for cluster {0} encountered name resolution "
                          + "error: {1}, scheduling DNS resolution backoff for {2} ns",
                  name, error, delayNanos);
          scheduledRefresh =
                  syncContext.schedule(
                          new DelayedNameResolverRefresh(), delayNanos, TimeUnit.NANOSECONDS,
                          timeService);
        }
      }
    }
  }

  private static class ClusterResolutionResult {
    // Endpoint addresses.
    private final List<EquivalentAddressGroup> addresses;
    // Config (include load balancing policy/config) for each priority in the cluster.
    private final Map<String, PriorityChildConfig> priorityChildConfigs;
    // List of priority names ordered in descending priorities.
    private final List<String> priorities;

    ClusterResolutionResult(List<EquivalentAddressGroup> addresses, String priority,
        PriorityChildConfig config) {
      this(addresses, Collections.singletonMap(priority, config),
          Collections.singletonList(priority));
    }

    ClusterResolutionResult(List<EquivalentAddressGroup> addresses,
        Map<String, PriorityChildConfig> configs, List<String> priorities) {
      this.addresses = addresses;
      this.priorityChildConfigs = configs;
      this.priorities = priorities;
    }
  }

  /**
   * Generates the config to be used in the priority LB policy for the single priority of
   * logical DNS cluster.
   *
   * <p>priority LB -> cluster_impl LB (single hardcoded priority) -> pick_first
   */
  private static PriorityChildConfig generateDnsBasedPriorityChildConfig(
      String cluster, @Nullable ServerInfo lrsServerInfo, @Nullable Long maxConcurrentRequests,
      @Nullable UpstreamTlsContext tlsContext, Map<String, Struct> filterMetadata,
      LoadBalancerRegistry lbRegistry, List<DropOverload> dropOverloads) {
    // Override endpoint-level LB policy with pick_first for logical DNS cluster.
    Object endpointLbConfig = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
        lbRegistry.getProvider("pick_first"), null);
    ClusterImplConfig clusterImplConfig =
        new ClusterImplConfig(cluster, null, lrsServerInfo, maxConcurrentRequests,
            dropOverloads, endpointLbConfig, tlsContext, filterMetadata);
    LoadBalancerProvider clusterImplLbProvider =
        lbRegistry.getProvider(XdsLbPolicies.CLUSTER_IMPL_POLICY_NAME);
    Object clusterImplPolicy = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
        clusterImplLbProvider, clusterImplConfig);
    return new PriorityChildConfig(clusterImplPolicy, false /* ignoreReresolution*/);
  }

  /**
   * Generates configs to be used in the priority LB policy for priorities in an EDS cluster.
   *
   * <p>priority LB -> cluster_impl LB (one per priority) -> (weighted_target LB
   * -> round_robin / least_request_experimental (one per locality)) / ring_hash_experimental
   */
  private static Map<String, PriorityChildConfig> generateEdsBasedPriorityChildConfigs(
      String cluster, @Nullable String edsServiceName, @Nullable ServerInfo lrsServerInfo,
      @Nullable Long maxConcurrentRequests, @Nullable UpstreamTlsContext tlsContext,
      Map<String, Struct> filterMetadata,
      @Nullable OutlierDetection outlierDetection, Object endpointLbConfig,
      LoadBalancerRegistry lbRegistry, Map<String,
      Map<Locality, Integer>> prioritizedLocalityWeights, List<DropOverload> dropOverloads) {
    Map<String, PriorityChildConfig> configs = new HashMap<>();
    for (String priority : prioritizedLocalityWeights.keySet()) {
      ClusterImplConfig clusterImplConfig =
          new ClusterImplConfig(cluster, edsServiceName, lrsServerInfo, maxConcurrentRequests,
              dropOverloads, endpointLbConfig, tlsContext, filterMetadata);
      LoadBalancerProvider clusterImplLbProvider =
          lbRegistry.getProvider(XdsLbPolicies.CLUSTER_IMPL_POLICY_NAME);
      Object priorityChildPolicy = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
          clusterImplLbProvider, clusterImplConfig);

      // If outlier detection has been configured we wrap the child policy in the outlier detection
      // load balancer.
      if (outlierDetection != null) {
        LoadBalancerProvider outlierDetectionProvider = lbRegistry.getProvider(
            "outlier_detection_experimental");
        priorityChildPolicy = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            outlierDetectionProvider,
            buildOutlierDetectionLbConfig(outlierDetection, priorityChildPolicy));
      }

      PriorityChildConfig priorityChildConfig =
          new PriorityChildConfig(priorityChildPolicy, true /* ignoreReresolution */);
      configs.put(priority, priorityChildConfig);
    }
    return configs;
  }

  /**
   * Converts {@link OutlierDetection} that represents the xDS configuration to {@link
   * OutlierDetectionLoadBalancerConfig} that the {@link io.grpc.util.OutlierDetectionLoadBalancer}
   * understands.
   */
  private static OutlierDetectionLoadBalancerConfig buildOutlierDetectionLbConfig(
      OutlierDetection outlierDetection, Object childConfig) {
    OutlierDetectionLoadBalancerConfig.Builder configBuilder
        = new OutlierDetectionLoadBalancerConfig.Builder();

    configBuilder.setChildConfig(childConfig);

    if (outlierDetection.intervalNanos() != null) {
      configBuilder.setIntervalNanos(outlierDetection.intervalNanos());
    }
    if (outlierDetection.baseEjectionTimeNanos() != null) {
      configBuilder.setBaseEjectionTimeNanos(outlierDetection.baseEjectionTimeNanos());
    }
    if (outlierDetection.maxEjectionTimeNanos() != null) {
      configBuilder.setMaxEjectionTimeNanos(outlierDetection.maxEjectionTimeNanos());
    }
    if (outlierDetection.maxEjectionPercent() != null) {
      configBuilder.setMaxEjectionPercent(outlierDetection.maxEjectionPercent());
    }

    SuccessRateEjection successRate = outlierDetection.successRateEjection();
    if (successRate != null) {
      OutlierDetectionLoadBalancerConfig.SuccessRateEjection.Builder
          successRateConfigBuilder = new OutlierDetectionLoadBalancerConfig
          .SuccessRateEjection.Builder();

      if (successRate.stdevFactor() != null) {
        successRateConfigBuilder.setStdevFactor(successRate.stdevFactor());
      }
      if (successRate.enforcementPercentage() != null) {
        successRateConfigBuilder.setEnforcementPercentage(successRate.enforcementPercentage());
      }
      if (successRate.minimumHosts() != null) {
        successRateConfigBuilder.setMinimumHosts(successRate.minimumHosts());
      }
      if (successRate.requestVolume() != null) {
        successRateConfigBuilder.setRequestVolume(successRate.requestVolume());
      }

      configBuilder.setSuccessRateEjection(successRateConfigBuilder.build());
    }

    FailurePercentageEjection failurePercentage = outlierDetection.failurePercentageEjection();
    if (failurePercentage != null) {
      OutlierDetectionLoadBalancerConfig.FailurePercentageEjection.Builder
          failurePercentageConfigBuilder = new OutlierDetectionLoadBalancerConfig
          .FailurePercentageEjection.Builder();

      if (failurePercentage.threshold() != null) {
        failurePercentageConfigBuilder.setThreshold(failurePercentage.threshold());
      }
      if (failurePercentage.enforcementPercentage() != null) {
        failurePercentageConfigBuilder.setEnforcementPercentage(
            failurePercentage.enforcementPercentage());
      }
      if (failurePercentage.minimumHosts() != null) {
        failurePercentageConfigBuilder.setMinimumHosts(failurePercentage.minimumHosts());
      }
      if (failurePercentage.requestVolume() != null) {
        failurePercentageConfigBuilder.setRequestVolume(failurePercentage.requestVolume());
      }

      configBuilder.setFailurePercentageEjection(failurePercentageConfigBuilder.build());
    }

    return configBuilder.build();
  }

  /**
   * Generates a string that represents the priority in the LB policy config. The string is unique
   * across priorities in all clusters and priorityName(c, p1) < priorityName(c, p2) iff p1 < p2.
   * The ordering is undefined for priorities in different clusters.
   */
  private static String priorityName(String cluster, int priority) {
    return cluster + "[child" + priority + "]";
  }

  /**
   * Generates a string that represents the locality in the LB policy config. The string is unique
   * across all localities in all clusters.
   */
  private static String localityName(Locality locality) {
    return "{region=\"" + locality.region()
        + "\", zone=\"" + locality.zone()
        + "\", sub_zone=\"" + locality.subZone()
        + "\"}";
  }
}
