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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.xds.LocalityStore.LocalityStoreImpl;
import io.grpc.xds.XdsClient.EndpointUpdate;
import io.grpc.xds.XdsClient.EndpointWatcher;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

/**
 * Load balancer for experimental_eds LB policy.
 */
final class EdsLoadBalancer extends LoadBalancer {

  // TODO(zdapeng): merge LocalityStore implementation inside this class after migration to
  // XdsClient is done.
  private final LocalityStore localityStore;
  private final XdsClient xdsClient;
  private final ChannelLogger channelLogger;

  @Nullable
  private String edsServiceName;

  @CheckForNull
  private EndpointWatcher endpointWatcher;

  EdsLoadBalancer(Helper helper, XdsClient xdsClient) {
    this(
        new LocalityStoreImpl(
            checkNotNull(helper, "helper"), LoadBalancerRegistry.getDefaultRegistry()),
        xdsClient,
        helper.getChannelLogger());
  }

  @VisibleForTesting
  EdsLoadBalancer(
      LocalityStore localityStore, XdsClient xdsClient, ChannelLogger channelLogger) {
    this.localityStore = checkNotNull(localityStore, "localityStore");
    this.xdsClient = checkNotNull(xdsClient, "xdsClient");
    this.channelLogger = checkNotNull(channelLogger, "channelLogger");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    channelLogger.log(ChannelLogLevel.DEBUG, "Received ResolvedAddresses '%s'", resolvedAddresses);

    Object lbConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    checkArgument(
        lbConfig != null && EdsConfig.class.isAssignableFrom(lbConfig.getClass()),
        "Expecting an EDS LB config, but the actual LB config is '%s'",
        lbConfig);

    EdsConfig edsConfig = (EdsConfig) lbConfig;
    String edsServiceName = edsConfig.name;

    // TODO(zdapeng): If localityPickingPolicy is changed, swap to new policy.
    // Right now we have only one default localityPickingPolicy (hardcoded RoundRobin),
    // so ignoring localityPickingPolicy for now.

    if (!edsServiceName.equals(this.edsServiceName)) {
      if (endpointWatcher != null) {
        // TODO(zdapeng): Maybe gracefully swap until the localities for the new watcher is READY?
        xdsClient.cancelEndpointDataWatch(endpointWatcher);
      }
      endpointWatcher = new EndpointWatcherImpl(localityStore);
      xdsClient.watchEndpointData(edsServiceName, endpointWatcher);
      this.edsServiceName = edsServiceName;
    }
  }

  @Override
  public void handleNameResolutionError(Status error) {
    channelLogger.log(ChannelLogLevel.DEBUG, "Name resolution error: '%s'", error);
    // NO-OP?
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
  }

  @Override
  public void shutdown() {
    channelLogger.log(ChannelLogLevel.DEBUG, "EDS load balancer is shutting down");

    if (endpointWatcher != null) {
      xdsClient.cancelEndpointDataWatch(endpointWatcher);
    }
    localityStore.reset();
  }

  static final class EdsConfig {

    /**
     * Name of cluster to query EDS for.
     */
    final String name;

    final Object localityPickingPolicy;

    public EdsConfig(String name, Object localityPickingPolicy) {
      this.name = checkNotNull(name, "name");
      this.localityPickingPolicy = checkNotNull(localityPickingPolicy);
    }
  }

  static final class EndpointWatcherImpl implements EndpointWatcher {

    private final LocalityStore localityStore;

    EndpointWatcherImpl(LocalityStore localityStore) {
      this.localityStore = localityStore;
    }

    @Override
    public void onEndpointChanged(EndpointUpdate update) {
      checkNotNull(update, "update");

      localityStore.updateLocalityStore(ImmutableMap.copyOf(update.localityInfoMap));
      localityStore.updateDropPercentage(ImmutableList.copyOf(update.dropOverloads));
    }

    @Override
    public void onError(Status error) {
      // ADS stream error, no known endpoint specific error yet.
    }
  }
}
