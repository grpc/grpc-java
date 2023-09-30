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
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.util.MultiChildLoadBalancer;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

/**
 * A {@link LoadBalancer} that provides least request load balancing based on
 * outstanding request counters.
 * It works by sampling a number of subchannels and picking the one with the
 * fewest amount of outstanding requests.
 * The default sampling amount of two is also known as
 * the "power of two choices" (P2C).
 */
final class LeastRequestLoadBalancer extends MultiChildLoadBalancer {
  private static final Status EMPTY_OK = Status.OK.withDescription("no subchannels ready");
  private static final EmptyPicker EMPTY_LR_PICKER = new EmptyPicker(EMPTY_OK);

  @VisibleForTesting
  static final Attributes.Key<Ref<ConnectivityStateInfo>> STATE_INFO =
      Attributes.Key.create("state-info");

  private final ThreadSafeRandom random;

  private LeastRequestPicker currentPicker = EMPTY_LR_PICKER;
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
  protected SubchannelPicker getSubchannelPicker(Map<Object, SubchannelPicker> childPickers) {
    throw new UnsupportedOperationException(
        "LeastRequestLoadBalancer uses its ChildLbStates, not these child pickers directly");
  }

  @Override
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    // Need to update choiceCount before calling super so that the updateBalancingState call has the
    // new value.  However, if the update fails we need to revert it.
    int oldChoiceCount = choiceCount;
    LeastRequestConfig config =
        (LeastRequestConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (config != null) {
      choiceCount = config.choiceCount;
    }

    boolean successfulUpdate = super.acceptResolvedAddresses(resolvedAddresses);

    if (!successfulUpdate) {
      choiceCount = oldChoiceCount;
    }

    return successfulUpdate;
  }

  @Override
  protected SubchannelPicker getErrorPicker(Status error) {
    return new EmptyPicker(error);
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
        updateBalancingState(CONNECTING, EMPTY_LR_PICKER);
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
  protected ChildLbState createChildLbState(Object key, Object policyConfig,
      SubchannelPicker initialPicker) {
    return new LeastRequestLbState(key, pickFirstLbProvider, policyConfig, initialPicker);
  }

  private void updateBalancingState(ConnectivityState state, LeastRequestPicker picker) {
    if (state != currentConnectivityState || !picker.isEquivalentTo(currentPicker)) {
      super.updateHelperBalancingState(state, picker);
      currentConnectivityState = state;
      currentPicker = picker;
    }
  }

  // This should only be used by tests
  @VisibleForTesting
  void setResolvingAddresses(boolean newValue) {
    super.resolvingAddresses = newValue;
  }

  @Override
  protected Collection<ChildLbState> getChildLbStates() {
    return super.getChildLbStates();
  }

  @Override
  protected ChildLbState getChildLbState(Object key) {
    return super.getChildLbState(key);
  }

  private static AtomicInteger getInFlights(ChildLbState childLbState) {
    return ((LeastRequestLbState)childLbState).activeRequests;
  }

  @VisibleForTesting
  abstract static class LeastRequestPicker extends SubchannelPicker {
    abstract boolean isEquivalentTo(LeastRequestPicker picker);

    abstract String getStatusString();
  }

  @VisibleForTesting
  static final class ReadyPicker extends LeastRequestPicker {
    private final List<ChildLbState> list; // non-empty
    private final int choiceCount;
    private final ThreadSafeRandom random;

    ReadyPicker(List<ChildLbState> list, int choiceCount, ThreadSafeRandom random) {
      checkArgument(!list.isEmpty(), "empty list");
      this.list = list;
      this.choiceCount = choiceCount;
      this.random = checkNotNull(random, "random");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      final ChildLbState childLbState = nextChildToUse();
      PickResult childResult = childLbState.getCurrentPicker().pickSubchannel(args);

      if (!childResult.getStatus().isOk() || childResult.getSubchannel() == null) {
        return childResult;
      }

      if (childResult.getStreamTracerFactory() != null) {
        // Already wrapped, so just use the current picker for selected child
        return childResult;
      } else {
        // Wrap the subchannel
        OutstandingRequestsTracingFactory factory =
            new OutstandingRequestsTracingFactory(getInFlights(childLbState));
        return PickResult.withSubchannel(childResult.getSubchannel(), factory);
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(ReadyPicker.class)
                        .add("list", list)
                        .add("choiceCount", choiceCount)
                        .toString();
    }

    private ChildLbState nextChildToUse() {
      ChildLbState candidate = list.get(random.nextInt(list.size()));
      for (int i = 0; i < choiceCount - 1; ++i) {
        ChildLbState sampled = list.get(random.nextInt(list.size()));
        if (getInFlights(sampled).get() < getInFlights(candidate).get()) {
          candidate = sampled;
        }
      }
      return candidate;
    }

    @VisibleForTesting
    List<ChildLbState> getList() {
      return list;
    }

    @Override
    String getStatusString() {
      if (list == null || list.isEmpty()) {
        return "";
      };

      String pickerStr = list.get(0).getCurrentPicker().toString();
      int beg = pickerStr.indexOf(", status=Status{");
      if (beg < 0) {
        return "";
      }
      int end = pickerStr.indexOf('}', beg);
      if (end < 0) {
        return "";
      }
      return pickerStr.substring(beg + ", status=".length(), end + 1);
    }

    @VisibleForTesting


    @Override
    boolean isEquivalentTo(LeastRequestPicker picker) {
      if (!(picker instanceof ReadyPicker)) {
        return false;
      }
      ReadyPicker other = (ReadyPicker) picker;
      // the lists cannot contain duplicate subchannels
      return other == this
          || ((list.size() == other.list.size() && new HashSet<>(list).containsAll(other.list))
                && choiceCount == other.choiceCount);
    }
  }

  @VisibleForTesting
  static final class EmptyPicker extends LeastRequestPicker {

    private final Status status;

    EmptyPicker(@Nonnull Status status) {
      this.status = Preconditions.checkNotNull(status, "status");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return status.isOk() ? PickResult.withNoResult() : PickResult.withError(status);
    }

    @Override
    boolean isEquivalentTo(LeastRequestPicker picker) {
      return picker instanceof EmptyPicker && (Objects.equal(status, ((EmptyPicker) picker).status)
          || (status.isOk() && ((EmptyPicker) picker).status.isOk()));
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(EmptyPicker.class).add("status", status).toString();
    }

    @Override
    String getStatusString() {
      if (status == null) {
        return "";
      }
      return status.toString();
    }
  }

  /**
   * A lighter weight Reference than AtomicReference.
   */
  static final class Ref<T> {
    T value;

    Ref(T value) {
      this.value = value;
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

    public LeastRequestLbState(Object key, LoadBalancerProvider policyProvider,
        Object childConfig, SubchannelPicker initialPicker) {
      super(key, policyProvider, childConfig, initialPicker);
    }

    int getActiveRequests() {
      return activeRequests.get();
    }
  }
}
