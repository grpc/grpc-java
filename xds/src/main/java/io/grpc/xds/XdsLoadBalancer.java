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

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.SHUTDOWN;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import io.grpc.Attributes;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.xds.XdsComms.AdsStreamCallback;
import io.grpc.xds.XdsLbState.SubchannelStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that uses the XDS protocol.
 */
final class XdsLoadBalancer extends LoadBalancer {

  @VisibleForTesting
  static final Attributes.Key<AtomicReference<ConnectivityStateInfo>> STATE_INFO =
      Attributes.Key.create("io.grpc.xds.XdsLoadBalancer.stateInfo");

  private final SubchannelStore subchannelStore;
  private final Helper helper;
  private final LoadBalancerRegistry lbRegistry;
  private final FallbackManager fallbackManager;

  private final AdsStreamCallback adsStreamCallback = new AdsStreamCallback() {

    @Override
    public void onWorking() {
      fallbackManager.balancerWorking = true;
      fallbackManager.cancelFallback();
    }

    @Override
    public void onError() {
      fallbackManager.balancerWorking = false;
      fallbackManager.maybeUseFallbackPolicy();
    }
  };

  @Nullable
  private XdsLbState xdsLbState;

  private LbConfig fallbackPolicy;

  XdsLoadBalancer(Helper helper, LoadBalancerRegistry lbRegistry, SubchannelStore subchannelStore) {
    this.helper = checkNotNull(helper, "helper");
    this.lbRegistry = lbRegistry;
    this.subchannelStore = subchannelStore;
    fallbackManager = new FallbackManager(helper, subchannelStore, lbRegistry);
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();
    Attributes attributes = resolvedAddresses.getAttributes();
    Map<String, ?> newRawLbConfig = checkNotNull(
        attributes.get(ATTR_LOAD_BALANCING_CONFIG), "ATTR_LOAD_BALANCING_CONFIG not available");

    ConfigOrError cfg =
        XdsLoadBalancerProvider.parseLoadBalancingConfigPolicy(newRawLbConfig, lbRegistry);
    if (cfg.getError() != null) {
      throw cfg.getError().asRuntimeException();
    }
    XdsConfig xdsConfig = (XdsConfig) cfg.getConfig();
    fallbackPolicy = xdsConfig.fallbackPolicy;
    fallbackManager.updateFallbackServers(servers, attributes, fallbackPolicy);
    fallbackManager.maybeStartFallbackTimer();
    handleNewConfig(xdsConfig);
    xdsLbState.handleResolvedAddressGroups(servers, attributes);
  }

  private void handleNewConfig(XdsConfig xdsConfig) {
    String newBalancerName = xdsConfig.newBalancerName;
    LbConfig childPolicy = xdsConfig.childPolicy;
    XdsComms xdsComms = null;
    if (xdsLbState != null) { // may release and re-use/shutdown xdsComms from current xdsLbState
      if (!newBalancerName.equals(xdsLbState.balancerName)) {
        xdsComms = xdsLbState.shutdownAndReleaseXdsComms();
        if (xdsComms != null) {
          xdsComms.shutdownChannel();
          fallbackManager.balancerWorking = false;
          xdsComms = null;
        }
      } else if (!Objects.equal(
          getPolicyNameOrNull(childPolicy),
          getPolicyNameOrNull(xdsLbState.childPolicy))) {
        String cancelMessage = "Changing loadbalancing mode";
        xdsComms = xdsLbState.shutdownAndReleaseXdsComms();
        // close the stream but reuse the channel
        if (xdsComms != null) {
          xdsComms.shutdownLbRpc(cancelMessage);
          fallbackManager.balancerWorking = false;
          xdsComms.refreshAdsStream();
        }
      } else { // effectively no change in policy, keep xdsLbState unchanged
        return;
      }
    }
    xdsLbState = new XdsLbState(
        newBalancerName, childPolicy, xdsComms, helper, subchannelStore, adsStreamCallback);
  }

  @Nullable
  private static String getPolicyNameOrNull(@Nullable LbConfig config) {
    if (config == null) {
      return null;
    }
    return config.getPolicyName();
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (xdsLbState != null) {
      if (fallbackManager.getFallbackBalancer() != null) {
        fallbackManager.getFallbackBalancer().handleNameResolutionError(error);
      } else {
        xdsLbState.handleNameResolutionError(error);
      }
    }
    // TODO: impl
    // else {
    //   helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new FailingPicker(error));
    // }
  }

  @Override
  public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    // xdsLbState should never be null here since handleSubchannelState cannot be called while the
    // lb is shutdown.
    if (newState.getState() == SHUTDOWN) {
      return;
    }

    if (fallbackManager.getFallbackBalancer() != null) {
      fallbackManager.getFallbackBalancer().handleSubchannelState(subchannel, newState);
    }
    if (subchannelStore.hasSubchannel(subchannel)) {
      if (newState.getState() == IDLE) {
        subchannel.requestConnection();
      }
      subchannel.getAttributes().get(STATE_INFO).set(newState);
      xdsLbState.handleSubchannelState(subchannel, newState);
      fallbackManager.maybeUseFallbackPolicy();
    }
  }

  @Override
  public void shutdown() {
    if (xdsLbState != null) {
      XdsComms xdsComms = xdsLbState.shutdownAndReleaseXdsComms();
      if (xdsComms != null) {
        xdsComms.shutdownChannel();
      }
      xdsLbState = null;
    }
    fallbackManager.cancelFallback();
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
  }

  @Nullable
  XdsLbState getXdsLbStateForTest() {
    return xdsLbState;
  }

  /**
   * Represents a successfully parsed and validated LoadBalancingConfig for XDS.
   */
  static final class XdsConfig {
    private final String newBalancerName;
    // TODO(carl-mastrangelo): make these Object's containing the fully parsed child configs.
    @Nullable
    private final LbConfig childPolicy;
    @Nullable
    private final LbConfig fallbackPolicy;

    XdsConfig(
        String newBalancerName, @Nullable LbConfig childPolicy, @Nullable LbConfig fallbackPolicy) {
      this.newBalancerName = checkNotNull(newBalancerName, "newBalancerName");
      this.childPolicy = childPolicy;
      this.fallbackPolicy = fallbackPolicy;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("newBalancerName", newBalancerName)
          .add("childPolicy", childPolicy)
          .add("fallbackPolicy", fallbackPolicy)
          .toString();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof XdsConfig)) {
        return false;
      }
      XdsConfig that = (XdsConfig) obj;
      return Objects.equal(this.newBalancerName, that.newBalancerName)
          && Objects.equal(this.childPolicy, that.childPolicy)
          && Objects.equal(this.fallbackPolicy, that.fallbackPolicy);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(newBalancerName, childPolicy, fallbackPolicy);
    }
  }
}
