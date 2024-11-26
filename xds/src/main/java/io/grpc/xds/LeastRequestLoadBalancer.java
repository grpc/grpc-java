/*
 * Copyright 2021 The gRPC Authors
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
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.LeastRequestLoadBalancerProvider.DEFAULT_CHOICE_COUNT;
import static io.grpc.xds.LeastRequestLoadBalancerProvider.MAX_CHOICE_COUNT;
import static io.grpc.xds.LeastRequestLoadBalancerProvider.MIN_CHOICE_COUNT;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.Attributes;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.util.MultiChildLoadBalancer;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link LoadBalancer} that provides least request load balancing based on
 * outstanding request counters.
 * It works by sampling a number of subchannels and picking the one with the
 * fewest amount of outstanding requests.
 * The default sampling amount of two is also known as
 * the "power of two choices" (P2C).
 */
final class LeastRequestLoadBalancer extends MultiChildLoadBalancer {
  private final ThreadSafeRandom random;

  private SubchannelPicker currentPicker = new EmptyPicker();
  private int choiceCount = DEFAULT_CHOICE_COUNT;

  LeastRequestLoadBalancer(Helper helper) {
    this(helper, ThreadSafeRandomImpl.instance);
  }

  @VisibleForTesting
  LeastRequestLoadBalancer(Helper helper, ThreadSafeRandom random) {
    super(helper);
    this.random = checkNotNull(random, "random");
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    // Need to update choiceCount before calling super so that the updateBalancingState call has the
    // new value.  However, if the update fails we need to revert it.
    int oldChoiceCount = choiceCount;
    LeastRequestConfig config =
        (LeastRequestConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (config != null) {
      choiceCount = config.choiceCount;
    }

    Status addressAcceptanceStatus = super.acceptResolvedAddresses(resolvedAddresses);

    if (!addressAcceptanceStatus.isOk()) {
      choiceCount = oldChoiceCount;
    }

    return addressAcceptanceStatus;
  }

  /**
   * Updates picker with the list of active subchannels (state == READY).
   *
   *  <p>
   * If no active subchannels exist, but some are in TRANSIENT_FAILURE then returns a picker
   * with all of the children in TF so that the application code will get an error from a varying
   * random one when it tries to get a subchannel.
   * </p>
   */
  @SuppressWarnings("ReferenceEquality")
  @Override
  protected void updateOverallBalancingState() {
    List<ChildLbState> activeList = getReadyChildren();
    if (activeList.isEmpty()) {
      // No READY subchannels, determine aggregate state and error status
      boolean isConnecting = false;
      List<ChildLbState> childrenInTf = new ArrayList<>();
      for (ChildLbState childLbState : getChildLbStates()) {
        ConnectivityState state = childLbState.getCurrentState();
        if (state == CONNECTING || state == IDLE) {
          isConnecting = true;
        } else if (state == TRANSIENT_FAILURE) {
          childrenInTf.add(childLbState);
        }
      }
      if (isConnecting) {
        updateBalancingState(CONNECTING, new EmptyPicker());
      } else {
        // Give it all the failing children and let it randomly pick among them
        updateBalancingState(TRANSIENT_FAILURE,
            new ReadyPicker(childrenInTf, choiceCount, random));
      }
    } else {
      updateBalancingState(READY, new ReadyPicker(activeList, choiceCount, random));
    }
  }

  @Override
  protected ChildLbState createChildLbState(Object key) {
    return new LeastRequestLbState(key, pickFirstLbProvider);
  }

  private void updateBalancingState(ConnectivityState state, SubchannelPicker picker) {
    if (state != currentConnectivityState || !picker.equals(currentPicker)) {
      getHelper().updateBalancingState(state, picker);
      currentConnectivityState = state;
      currentPicker = picker;
    }
  }

  /**
   * This should ONLY be used by tests.
   */
  @VisibleForTesting
  void setResolvingAddresses(boolean newValue) {
    super.resolvingAddresses = newValue;
  }

  // Expose for tests in this package.
  private static AtomicInteger getInFlights(ChildLbState childLbState) {
    return ((LeastRequestLbState)childLbState).activeRequests;
  }

  @VisibleForTesting
  static final class ReadyPicker extends SubchannelPicker {
    private final List<SubchannelPicker> childPickers; // non-empty
    private final List<AtomicInteger> childInFlights; // 1:1 with childPickers
    private final int choiceCount;
    private final ThreadSafeRandom random;
    private final int hashCode;

    ReadyPicker(List<ChildLbState> childLbStates, int choiceCount, ThreadSafeRandom random) {
      checkArgument(!childLbStates.isEmpty(), "empty list");
      this.childPickers = new ArrayList<>(childLbStates.size());
      this.childInFlights = new ArrayList<>(childLbStates.size());
      for (ChildLbState state : childLbStates) {
        childPickers.add(state.getCurrentPicker());
        childInFlights.add(getInFlights(state));
      }
      this.choiceCount = choiceCount;
      this.random = checkNotNull(random, "random");

      int sum = 0;
      for (SubchannelPicker child : childPickers) {
        sum += child.hashCode();
      }
      this.hashCode = sum ^ choiceCount;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      int child = nextChildToUse();
      PickResult childResult = childPickers.get(child).pickSubchannel(args);

      if (!childResult.getStatus().isOk() || childResult.getSubchannel() == null) {
        return childResult;
      }

      if (childResult.getStreamTracerFactory() != null) {
        // Already wrapped, so just use the current picker for selected child
        return childResult;
      } else {
        // Wrap the subchannel
        OutstandingRequestsTracingFactory factory =
            new OutstandingRequestsTracingFactory(childInFlights.get(child));
        return PickResult.withSubchannel(childResult.getSubchannel(), factory);
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(ReadyPicker.class)
                        .add("list", childPickers)
                        .add("choiceCount", choiceCount)
                        .toString();
    }

    private int nextChildToUse() {
      int candidate = random.nextInt(childPickers.size());
      for (int i = 0; i < choiceCount - 1; ++i) {
        int sampled = random.nextInt(childPickers.size());
        if (childInFlights.get(sampled).get() < childInFlights.get(candidate).get()) {
          candidate = sampled;
        }
      }
      return candidate;
    }

    @VisibleForTesting
    List<SubchannelPicker> getChildPickers() {
      return childPickers;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof ReadyPicker)) {
        return false;
      }
      ReadyPicker other = (ReadyPicker) o;
      if (other == this) {
        return true;
      }
      // the lists cannot contain duplicate children
      return hashCode == other.hashCode
          && choiceCount == other.choiceCount
          && childPickers.size() == other.childPickers.size()
          && new HashSet<>(childPickers).containsAll(other.childPickers);
    }
  }

  @VisibleForTesting
  static final class EmptyPicker extends SubchannelPicker {
    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return PickResult.withNoResult();
    }

    @Override
    public int hashCode() {
      return getClass().hashCode();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof EmptyPicker;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(EmptyPicker.class).toString();
    }
  }

  private static final class OutstandingRequestsTracingFactory extends
      ClientStreamTracer.Factory {
    private final AtomicInteger inFlights;

    private OutstandingRequestsTracingFactory(AtomicInteger inFlights) {
      this.inFlights = checkNotNull(inFlights, "inFlights");
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
      return new ClientStreamTracer() {
        @Override
        public void streamCreated(Attributes transportAttrs, Metadata headers) {
          inFlights.incrementAndGet();
        }

        @Override
        public void streamClosed(Status status) {
          inFlights.decrementAndGet();
        }
      };
    }
  }

  static final class LeastRequestConfig {
    final int choiceCount;

    LeastRequestConfig(int choiceCount) {
      checkArgument(choiceCount >= MIN_CHOICE_COUNT, "choiceCount <= 1");
      // Even though a choiceCount value larger than 2 is currently considered valid in xDS
      // we restrict it to 10 here as specified in "A48: xDS Least Request LB Policy".
      this.choiceCount = Math.min(choiceCount, MAX_CHOICE_COUNT);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("choiceCount", choiceCount)
          .toString();
    }
  }

  protected class LeastRequestLbState extends ChildLbState {
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    public LeastRequestLbState(Object key, LoadBalancerProvider policyProvider) {
      super(key, policyProvider);
    }

    int getActiveRequests() {
      return activeRequests.get();
    }

    @Override
    protected ChildLbStateHelper createChildHelper() {
      return new ChildLbStateHelper() {
        @Override
        public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
          super.updateBalancingState(newState, newPicker);
          if (!resolvingAddresses && newState == IDLE) {
            getLb().requestConnection();
          }
        }
      };
    }
  }
}
