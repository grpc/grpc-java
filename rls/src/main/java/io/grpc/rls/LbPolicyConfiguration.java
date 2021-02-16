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

package io.grpc.rls;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.internal.ObjectPool;
import io.grpc.rls.ChildLoadBalancerHelper.ChildLoadBalancerHelperProvider;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import io.grpc.util.ForwardingLoadBalancerHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/** Configuration for RLS load balancing policy. */
final class LbPolicyConfiguration {

  private final RouteLookupConfig routeLookupConfig;
  private final ChildLoadBalancingPolicy policy;

  LbPolicyConfiguration(
      RouteLookupConfig routeLookupConfig, ChildLoadBalancingPolicy policy) {
    this.routeLookupConfig = checkNotNull(routeLookupConfig, "routeLookupConfig");
    this.policy = checkNotNull(policy, "policy");
  }

  RouteLookupConfig getRouteLookupConfig() {
    return routeLookupConfig;
  }

  ChildLoadBalancingPolicy getLoadBalancingPolicy() {
    return policy;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    LbPolicyConfiguration that = (LbPolicyConfiguration) o;
    return Objects.equals(routeLookupConfig, that.routeLookupConfig)
        && Objects.equals(policy, that.policy);
  }

  @Override
  public int hashCode() {
    return Objects.hash(routeLookupConfig, policy);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("routeLookupConfig", routeLookupConfig)
        .add("policy", policy)
        .toString();
  }

  /** ChildLoadBalancingPolicy is an elected child policy to delegate requests. */
  static final class ChildLoadBalancingPolicy {

    private final Map<String, Object> effectiveRawChildPolicy;
    private final LoadBalancerProvider effectiveLbProvider;
    private final String targetFieldName;

    @VisibleForTesting
    ChildLoadBalancingPolicy(
        String targetFieldName,
        Map<String, Object> effectiveRawChildPolicy,
        LoadBalancerProvider effectiveLbProvider) {
      checkArgument(
          targetFieldName != null && !targetFieldName.isEmpty(),
          "targetFieldName cannot be empty or null");
      this.targetFieldName = targetFieldName;
      this.effectiveRawChildPolicy =
          checkNotNull(effectiveRawChildPolicy, "effectiveRawChildPolicy");
      this.effectiveLbProvider = checkNotNull(effectiveLbProvider, "effectiveLbProvider");
    }

    /** Creates ChildLoadBalancingPolicy. */
    @SuppressWarnings("unchecked")
    static ChildLoadBalancingPolicy create(
        String childPolicyConfigTargetFieldName, List<Map<String, ?>> childPolicies)
        throws InvalidChildPolicyConfigException {
      Map<String, Object> effectiveChildPolicy = null;
      LoadBalancerProvider effectiveLbProvider = null;
      List<String> policyTried = new ArrayList<>();

      LoadBalancerRegistry lbRegistry = LoadBalancerRegistry.getDefaultRegistry();
      for (Map<String, ?> childPolicy : childPolicies) {
        if (childPolicy.isEmpty()) {
          continue;
        }
        if (childPolicy.size() != 1) {
          throw
              new InvalidChildPolicyConfigException(
                  "childPolicy should have exactly one loadbalancing policy");
        }
        String policyName = childPolicy.keySet().iterator().next();
        LoadBalancerProvider provider = lbRegistry.getProvider(policyName);
        if (provider != null) {
          effectiveLbProvider = provider;
          effectiveChildPolicy = Collections.unmodifiableMap(childPolicy);
          break;
        }
        policyTried.add(policyName);
      }
      if (effectiveChildPolicy == null) {
        throw
            new InvalidChildPolicyConfigException(
                String.format("no valid childPolicy found, policy tried: %s", policyTried));
      }
      return
          new ChildLoadBalancingPolicy(
              childPolicyConfigTargetFieldName,
              (Map<String, Object>) effectiveChildPolicy.values().iterator().next(),
              effectiveLbProvider);
    }

    /** Creates a child load balancer config for given target from elected raw child policy. */
    Map<String, ?> getEffectiveChildPolicy(String target) {
      Map<String, Object> childPolicy = new HashMap<>(effectiveRawChildPolicy);
      childPolicy.put(targetFieldName, target);
      return childPolicy;
    }

    /** Returns the elected child {@link LoadBalancerProvider}. */
    LoadBalancerProvider getEffectiveLbProvider() {
      return effectiveLbProvider;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ChildLoadBalancingPolicy that = (ChildLoadBalancingPolicy) o;
      return Objects.equals(effectiveRawChildPolicy, that.effectiveRawChildPolicy)
          && Objects.equals(effectiveLbProvider, that.effectiveLbProvider)
          && Objects.equals(targetFieldName, that.targetFieldName);
    }

    @Override
    public int hashCode() {
      return Objects.hash(effectiveRawChildPolicy, effectiveLbProvider, targetFieldName);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("effectiveRawChildPolicy", effectiveRawChildPolicy)
          .add("effectiveLbProvider", effectiveLbProvider)
          .add("childPolicyConfigTargetFieldName", targetFieldName)
          .toString();
    }
  }

  /** Factory for {@link ChildPolicyWrapper}. */
  static final class RefCountedChildPolicyWrapperFactory {
    @VisibleForTesting
    final Map<String /* target */, RefCountedChildPolicyWrapper> childPolicyMap =
        new HashMap<>();

    private final ChildLoadBalancerHelperProvider childLbHelperProvider;
    private final ChildLbStatusListener childLbStatusListener;

    public RefCountedChildPolicyWrapperFactory(
        ChildLoadBalancerHelperProvider childLbHelperProvider,
        ChildLbStatusListener childLbStatusListener) {
      this.childLbHelperProvider = checkNotNull(childLbHelperProvider, "childLbHelperProvider");
      this.childLbStatusListener = checkNotNull(childLbStatusListener, "childLbStatusListener");
    }

    ChildPolicyWrapper createOrGet(String target) {
      // TODO(creamsoup) check if the target is valid or not
      RefCountedChildPolicyWrapper pooledChildPolicyWrapper = childPolicyMap.get(target);
      if (pooledChildPolicyWrapper == null) {
        ChildPolicyWrapper childPolicyWrapper =
            new ChildPolicyWrapper(target, childLbHelperProvider, childLbStatusListener);
        pooledChildPolicyWrapper = RefCountedChildPolicyWrapper.of(childPolicyWrapper);
        childPolicyMap.put(target, pooledChildPolicyWrapper);
      }

      return pooledChildPolicyWrapper.getObject();
    }

    void release(ChildPolicyWrapper childPolicyWrapper) {
      checkNotNull(childPolicyWrapper, "childPolicyWrapper");
      String target = childPolicyWrapper.getTarget();
      RefCountedChildPolicyWrapper existing = childPolicyMap.get(target);
      checkState(existing != null, "Cannot access already released object");
      existing.returnObject(childPolicyWrapper);
      if (existing.isReleased()) {
        childPolicyMap.remove(target);
      }
    }
  }

  /**
   * ChildPolicyWrapper is a wrapper class for child load balancing policy with associated helper /
   * utility classes to manage the child policy.
   */
  static final class ChildPolicyWrapper {

    private final String target;
    private final ChildPolicyReportingHelper helper;
    private volatile SubchannelPicker picker;
    private ConnectivityState state;

    public ChildPolicyWrapper(
        String target,
        ChildLoadBalancerHelperProvider childLbHelperProvider,
        ChildLbStatusListener childLbStatusListener) {
      this.target = target;
      this.helper =
          new ChildPolicyReportingHelper(childLbHelperProvider, childLbStatusListener);
    }

    String getTarget() {
      return target;
    }

    SubchannelPicker getPicker() {
      return picker;
    }

    ChildPolicyReportingHelper getHelper() {
      return helper;
    }

    void refreshState() {
      helper.updateBalancingState(state, picker);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("target", target)
          .add("picker", picker)
          .add("state", state)
          .toString();
    }

    /**
     * A delegating {@link io.grpc.LoadBalancer.Helper} maintains status of {@link
     * ChildPolicyWrapper} when {@link Subchannel} status changed. This helper is used between child
     * policy and parent load-balancer where each picker in child policy is governed by a governing
     * picker (RlsPicker). The governing picker will be reported back to the parent load-balancer.
     */
    final class ChildPolicyReportingHelper extends ForwardingLoadBalancerHelper {

      private final ChildLoadBalancerHelper delegate;
      private final ChildLbStatusListener listener;

      ChildPolicyReportingHelper(
          ChildLoadBalancerHelperProvider childHelperProvider,
          ChildLbStatusListener listener) {
        checkNotNull(childHelperProvider, "childHelperProvider");
        this.delegate = childHelperProvider.forTarget(getTarget());
        this.listener = checkNotNull(listener, "listener");
      }

      @Override
      protected Helper delegate() {
        return delegate;
      }

      @Override
      public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
        picker = newPicker;
        state = newState;
        super.updateBalancingState(newState, newPicker);
        listener.onStatusChanged(newState);
      }
    }
  }

  /** Listener for child lb status change events. */
  interface ChildLbStatusListener {

    /** Notifies when child lb status changes. */
    void onStatusChanged(ConnectivityState newState);
  }

  private static final class RefCountedChildPolicyWrapper
      implements ObjectPool<ChildPolicyWrapper> {

    private final AtomicLong refCnt = new AtomicLong();
    @Nullable
    private ChildPolicyWrapper childPolicyWrapper;

    private RefCountedChildPolicyWrapper(ChildPolicyWrapper childPolicyWrapper) {
      this.childPolicyWrapper = checkNotNull(childPolicyWrapper, "childPolicyWrapper");
    }

    @Override
    public ChildPolicyWrapper getObject() {
      checkState(!isReleased(), "ChildPolicyWrapper is already released");
      refCnt.getAndIncrement();
      return childPolicyWrapper;
    }

    @Override
    @Nullable
    public ChildPolicyWrapper returnObject(Object object) {
      checkState(
          !isReleased(),
          "cannot return already released ChildPolicyWrapper, this is possibly a bug.");
      checkState(
          childPolicyWrapper == object,
          "returned object doesn't match the pooled childPolicyWrapper");
      long newCnt = refCnt.decrementAndGet();
      checkState(newCnt != -1, "Cannot return never pooled childPolicyWrapper");
      if (newCnt == 0) {
        childPolicyWrapper = null;
      }
      return null;
    }

    boolean isReleased() {
      return childPolicyWrapper == null;
    }

    static RefCountedChildPolicyWrapper of(ChildPolicyWrapper childPolicyWrapper) {
      return new RefCountedChildPolicyWrapper(childPolicyWrapper);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("object", childPolicyWrapper)
          .add("refCnt", refCnt.get())
          .toString();
    }
  }

  /** Exception thrown when attempting to parse child policy encountered parsing issue. */
  static final class InvalidChildPolicyConfigException extends Exception {

    private static final long serialVersionUID = 0L;

    InvalidChildPolicyConfigException(String message) {
      super(message);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
      // no stack trace above this point
      return this;
    }
  }
}
