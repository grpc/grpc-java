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
import static io.grpc.xds.XdsSubchannelPickers.BUFFER_PICKER;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.ConnectivityState;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.ObjectPool;
import io.grpc.util.ForwardingClientStreamTracer;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.xds.ClusterImplLoadBalancerProvider.ClusterImplConfig;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsNameResolverProvider.CallCounterProvider;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
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
      Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_CIRCUIT_BREAKING"));

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
  private LoadStatsStore loadStatsStore;
  private RequestLimitingLbHelper childLbHelper;
  private LoadBalancer childLb;

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
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    Attributes attributes = resolvedAddresses.getAttributes();
    if (xdsClientPool == null) {
      xdsClientPool = attributes.get(XdsAttributes.XDS_CLIENT_POOL);
      xdsClient = xdsClientPool.getObject();
    }
    if (callCounterProvider == null) {
      callCounterProvider = attributes.get(XdsAttributes.CALL_COUNTER_PROVIDER);
    }
    ClusterImplConfig config =
        (ClusterImplConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (cluster == null) {
      cluster = config.cluster;
      edsServiceName = config.edsServiceName;
      childLbHelper = new RequestLimitingLbHelper(
          callCounterProvider.getOrCreate(config.cluster, config.edsServiceName));
      childLb = config.childPolicy.getProvider().newLoadBalancer(childLbHelper);
      // Assume load report server does not change throughout cluster lifetime.
      if (config.lrsServerName != null) {
        if (config.lrsServerName.isEmpty()) {
          loadStatsStore = xdsClient.addClientStats(cluster, edsServiceName);
        } else {
          logger.log(XdsLogLevel.WARNING, "Can only report load to the same management server");
        }
      }
    }
    childLbHelper.updateDropPolicies(config.dropCategories);
    childLbHelper.updateMaxConcurrentRequests(config.maxConcurrentRequests);
    if (loadStatsStore != null) {
      attributes = attributes.toBuilder()
          .set(XdsAttributes.ATTR_CLUSTER_SERVICE_LOAD_STATS_STORE, loadStatsStore).build();
    }
    childLb.handleResolvedAddresses(
        resolvedAddresses.toBuilder()
            .setAttributes(attributes)
            .setLoadBalancingPolicyConfig(config.childPolicy.getConfig())
            .build());
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (childLb != null) {
      childLb.handleNameResolutionError(error);
    } else {
      helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new ErrorPicker(error));
    }
  }

  @Override
  public void shutdown() {
    if (loadStatsStore != null) {
      xdsClient.removeClientStats(cluster, edsServiceName);
    }
    if (childLb != null) {
      childLb.shutdown();
      childLbHelper = null;
    }
    if (xdsClient != null) {
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
  }

  private final class RequestLimitingLbHelper extends ForwardingLoadBalancerHelper {
    private final AtomicLong requestCount;
    private ConnectivityState currentState = ConnectivityState.IDLE;
    private SubchannelPicker currentPicker = BUFFER_PICKER;
    private List<DropOverload> dropPolicies = Collections.emptyList();
    private long maxConcurrentRequests = DEFAULT_PER_CLUSTER_MAX_CONCURRENT_REQUESTS;

    private RequestLimitingLbHelper(AtomicLong requestCount) {
      this.requestCount = requestCount;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      currentState = newState;
      currentPicker =  newPicker;
      SubchannelPicker picker =
          new RequestLimitingSubchannelPicker(newPicker, dropPolicies, maxConcurrentRequests);
      delegate().updateBalancingState(newState, picker);
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

    private class RequestLimitingSubchannelPicker extends SubchannelPicker {
      private final SubchannelPicker delegate;
      private final List<DropOverload> dropPolicies;
      private final long maxConcurrentRequests;

      private RequestLimitingSubchannelPicker(SubchannelPicker delegate,
          List<DropOverload> dropPolicies, long maxConcurrentRequests) {
        this.delegate = delegate;
        this.dropPolicies = dropPolicies;
        this.maxConcurrentRequests = maxConcurrentRequests;
      }

      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        for (DropOverload dropOverload : dropPolicies) {
          int rand = random.nextInt(1_000_000);
          if (rand < dropOverload.getDropsPerMillion()) {
            logger.log(XdsLogLevel.INFO, "Drop request with category: {0}",
                dropOverload.getCategory());
            if (loadStatsStore != null) {
              loadStatsStore.recordDroppedRequest(dropOverload.getCategory());
            }
            return PickResult.withDrop(
                Status.UNAVAILABLE.withDescription("Dropped: " + dropOverload.getCategory()));
          }
        }
        PickResult result = delegate.pickSubchannel(args);
        if (enableCircuitBreaking) {
          if (result.getStatus().isOk() && result.getSubchannel() != null) {
            if (requestCount.get() >= maxConcurrentRequests) {
              if (loadStatsStore != null) {
                loadStatsStore.recordDroppedRequest();
              }
              return PickResult.withDrop(Status.UNAVAILABLE.withDescription(
                  "Cluster max concurrent requests limit exceeded"));
            } else {
              ClientStreamTracer.Factory tracerFactory = new RequestCountingStreamTracerFactory(
                  result.getStreamTracerFactory(), requestCount);
              return PickResult.withSubchannel(result.getSubchannel(), tracerFactory);
            }
          }
        }
        return result;
      }
    }
  }

  /**
   * Counts the number of outstanding requests.
   */
  private static final class RequestCountingStreamTracerFactory
      extends ClientStreamTracer.Factory {
    @Nullable
    private final ClientStreamTracer.Factory delegate;
    private final AtomicLong counter;

    private RequestCountingStreamTracerFactory(@Nullable ClientStreamTracer.Factory delegate,
        AtomicLong counter) {
      this.delegate = delegate;
      this.counter = counter;
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
      counter.incrementAndGet();
      if (delegate == null) {
        return new ClientStreamTracer() {
          @Override
          public void streamClosed(Status status) {
            counter.decrementAndGet();
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
          counter.decrementAndGet();
          delegate().streamClosed(status);
        }
      };
    }
  }
}
