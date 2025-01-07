/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A pluggable component that receives resolved addresses from {@link NameResolver} and provides the
 * channel a usable subchannel when asked.
 *
 * <h3>Overview</h3>
 *
 * <p>A LoadBalancer typically implements three interfaces:
 * <ol>
 *   <li>{@link LoadBalancer} is the main interface.  All methods on it are invoked sequentially
 *       in the same <strong>synchronization context</strong> (see next section) as returned by
 *       {@link io.grpc.LoadBalancer.Helper#getSynchronizationContext}.  It receives the results
 *       from the {@link NameResolver}, updates of subchannels' connectivity states, and the
 *       channel's request for the LoadBalancer to shutdown.</li>
 *   <li>{@link SubchannelPicker SubchannelPicker} does the actual load-balancing work.  It selects
 *       a {@link Subchannel Subchannel} for each new RPC.</li>
 *   <li>{@link Factory Factory} creates a new {@link LoadBalancer} instance.
 * </ol>
 *
 * <p>{@link Helper Helper} is implemented by gRPC library and provided to {@link Factory
 * Factory}. It provides functionalities that a {@code LoadBalancer} implementation would typically
 * need.
 *
 * <h3>The Synchronization Context</h3>
 *
 * <p>All methods on the {@link LoadBalancer} interface are called from a Synchronization Context,
 * meaning they are serialized, thus the balancer implementation doesn't need to worry about
 * synchronization among them.  {@link io.grpc.LoadBalancer.Helper#getSynchronizationContext}
 * allows implementations to schedule tasks to be run in the same Synchronization Context, with or
 * without a delay, thus those tasks don't need to worry about synchronizing with the balancer
 * methods.
 * 
 * <p>However, the actual running thread may be the network thread, thus the following rules must be
 * followed to prevent blocking or even dead-locking in a network:
 *
 * <ol>
 *
 *   <li><strong>Never block in the Synchronization Context</strong>.  The callback methods must
 *   return quickly.  Examples or work that must be avoided: CPU-intensive calculation, waiting on
 *   synchronization primitives, blocking I/O, blocking RPCs, etc.</li>
 *
 *   <li><strong>Avoid calling into other components with lock held</strong>.  The Synchronization
 *   Context may be under a lock, e.g., the transport lock of OkHttp.  If your LoadBalancer holds a
 *   lock in a callback method (e.g., {@link #handleResolvedAddresses handleResolvedAddresses()})
 *   while calling into another method that also involves locks, be cautious of deadlock.  Generally
 *   you wouldn't need any locking in the LoadBalancer if you follow the canonical implementation
 *   pattern below.</li>
 *
 * </ol>
 *
 * <h3>The canonical implementation pattern</h3>
 *
 * <p>A {@link LoadBalancer} keeps states like the latest addresses from NameResolver, the
 * Subchannel(s) and their latest connectivity states.  These states are mutated within the
 * Synchronization Context,
 *
 * <p>A typical {@link SubchannelPicker SubchannelPicker} holds a snapshot of these states.  It may
 * have its own states, e.g., a picker from a round-robin load-balancer may keep a pointer to the
 * next Subchannel, which are typically mutated by multiple threads.  The picker should only mutate
 * its own state, and should not mutate or re-acquire the states of the LoadBalancer.  This way the
 * picker only needs to synchronize its own states, which is typically trivial to implement.
 *
 * <p>When the LoadBalancer states changes, e.g., Subchannels has become or stopped being READY, and
 * we want subsequent RPCs to use the latest list of READY Subchannels, LoadBalancer would create a
 * new picker, which holds a snapshot of the latest Subchannel list.  Refer to the javadoc of {@link
 * io.grpc.LoadBalancer.SubchannelStateListener#onSubchannelState onSubchannelState()} how to do
 * this properly.
 *
 * <p>No synchronization should be necessary between LoadBalancer and its pickers if you follow
 * the pattern above.  It may be possible to implement in a different way, but that would usually
 * result in more complicated threading.
 *
 * @since 1.2.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
@NotThreadSafe
public abstract class LoadBalancer {

  @Internal
  @NameResolver.ResolutionResultAttr
  public static final Attributes.Key<Map<String, ?>> ATTR_HEALTH_CHECKING_CONFIG =
      Attributes.Key.create("internal:health-checking-config");

  @Internal
  public static final LoadBalancer.CreateSubchannelArgs.Key<LoadBalancer.SubchannelStateListener>
      HEALTH_CONSUMER_LISTENER_ARG_KEY =
      LoadBalancer.CreateSubchannelArgs.Key.create("internal:health-check-consumer-listener");

  @Internal
  public static final LoadBalancer.CreateSubchannelArgs.Key<Boolean>
      DISABLE_SUBCHANNEL_RECONNECT_KEY =
      LoadBalancer.CreateSubchannelArgs.Key.createWithDefault(
          "internal:disable-subchannel-reconnect", Boolean.FALSE);

  @Internal
  public static final Attributes.Key<Boolean>
      HAS_HEALTH_PRODUCER_LISTENER_KEY =
      Attributes.Key.create("internal:has-health-check-producer-listener");

  public static final Attributes.Key<Boolean> IS_PETIOLE_POLICY =
      Attributes.Key.create("io.grpc.IS_PETIOLE_POLICY");

  /**
   * A picker that always returns an erring pick.
   *
   * @deprecated Use {@code new FixedResultPicker(PickResult.withNoResult())} instead.
   */
  @Deprecated
  public static final SubchannelPicker EMPTY_PICKER = new SubchannelPicker() {
    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return PickResult.withNoResult();
    }

    @Override
    public String toString() {
      return "EMPTY_PICKER";
    }
  };

  private int recursionCount;

  /**
   * Handles newly resolved server groups and metadata attributes from name resolution system.
   * {@code servers} contained in {@link EquivalentAddressGroup} should be considered equivalent
   * but may be flattened into a single list if needed.
   *
   * <p>Implementations should not modify the given {@code servers}.
   *
   * @param resolvedAddresses the resolved server addresses, attributes, and config.
   * @since 1.21.0
   */
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    if (recursionCount++ == 0) {
      // Note that the information about the addresses actually being accepted will be lost
      // if you rely on this method for backward compatibility.
      acceptResolvedAddresses(resolvedAddresses);
    }
    recursionCount = 0;
  }

  /**
   * Accepts newly resolved addresses from the name resolution system. The {@link
   * EquivalentAddressGroup} addresses should be considered equivalent but may be flattened into a
   * single list if needed.
   *
   * <p>Implementations can choose to reject the given addresses by returning {@code false}.
   *
   * <p>Implementations should not modify the given {@code addresses}.
   *
   * @param resolvedAddresses the resolved server addresses, attributes, and config.
   * @return {@code true} if the resolved addresses were accepted. {@code false} if rejected.
   * @since 1.49.0
   */
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    if (resolvedAddresses.getAddresses().isEmpty()
        && !canHandleEmptyAddressListFromNameResolution()) {
      Status unavailableStatus = Status.UNAVAILABLE.withDescription(
              "NameResolver returned no usable address. addrs=" + resolvedAddresses.getAddresses()
                      + ", attrs=" + resolvedAddresses.getAttributes());
      handleNameResolutionError(unavailableStatus);
      return unavailableStatus;
    } else {
      if (recursionCount++ == 0) {
        handleResolvedAddresses(resolvedAddresses);
      }
      recursionCount = 0;

      return Status.OK;
    }
  }

  /**
   * Represents a combination of the resolved server address, associated attributes and a load
   * balancing policy config.  The config is from the {@link
   * LoadBalancerProvider#parseLoadBalancingPolicyConfig(Map)}.
   *
   * @since 1.21.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/11657")
  public static final class ResolvedAddresses {
    private final List<EquivalentAddressGroup> addresses;
    @NameResolver.ResolutionResultAttr
    private final Attributes attributes;
    @Nullable
    private final Object loadBalancingPolicyConfig;
    // Make sure to update toBuilder() below!

    private ResolvedAddresses(
        List<EquivalentAddressGroup> addresses,
        @NameResolver.ResolutionResultAttr Attributes attributes,
        Object loadBalancingPolicyConfig) {
      this.addresses =
          Collections.unmodifiableList(new ArrayList<>(checkNotNull(addresses, "addresses")));
      this.attributes = checkNotNull(attributes, "attributes");
      this.loadBalancingPolicyConfig = loadBalancingPolicyConfig;
    }

    /**
     * Factory for constructing a new Builder.
     *
     * @since 1.21.0
     */
    public static Builder newBuilder() {
      return new Builder();
    }

    /**
     * Converts this back to a builder.
     *
     * @since 1.21.0
     */
    public Builder toBuilder() {
      return newBuilder()
          .setAddresses(addresses)
          .setAttributes(attributes)
          .setLoadBalancingPolicyConfig(loadBalancingPolicyConfig);
    }

    /**
     * Gets the server addresses.
     *
     * @since 1.21.0
     */
    public List<EquivalentAddressGroup> getAddresses() {
      return addresses;
    }

    /**
     * Gets the attributes associated with these addresses.  If this was not previously set,
     * {@link Attributes#EMPTY} will be returned.
     *
     * @since 1.21.0
     */
    @NameResolver.ResolutionResultAttr
    public Attributes getAttributes() {
      return attributes;
    }

    /**
     * Gets the domain specific load balancing policy.  This is the config produced by
     * {@link LoadBalancerProvider#parseLoadBalancingPolicyConfig(Map)}.
     *
     * @since 1.21.0
     */
    @Nullable
    public Object getLoadBalancingPolicyConfig() {
      return loadBalancingPolicyConfig;
    }

    /**
     * Builder for {@link ResolvedAddresses}.
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
    public static final class Builder {
      private List<EquivalentAddressGroup> addresses;
      @NameResolver.ResolutionResultAttr
      private Attributes attributes = Attributes.EMPTY;
      @Nullable
      private Object loadBalancingPolicyConfig;

      Builder() {}

      /**
       * Sets the addresses.  This field is required.
       *
       * @return this.
       */
      public Builder setAddresses(List<EquivalentAddressGroup> addresses) {
        this.addresses = addresses;
        return this;
      }

      /**
       * Sets the attributes.  This field is optional; if not called, {@link Attributes#EMPTY}
       * will be used.
       *
       * @return this.
       */
      public Builder setAttributes(@NameResolver.ResolutionResultAttr Attributes attributes) {
        this.attributes = attributes;
        return this;
      }

      /**
       * Sets the load balancing policy config. This field is optional.
       *
       * @return this.
       */
      public Builder setLoadBalancingPolicyConfig(@Nullable Object loadBalancingPolicyConfig) {
        this.loadBalancingPolicyConfig = loadBalancingPolicyConfig;
        return this;
      }

      /**
       * Constructs the {@link ResolvedAddresses}.
       */
      public ResolvedAddresses build() {
        return new ResolvedAddresses(addresses, attributes, loadBalancingPolicyConfig);
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("addresses", addresses)
          .add("attributes", attributes)
          .add("loadBalancingPolicyConfig", loadBalancingPolicyConfig)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(addresses, attributes, loadBalancingPolicyConfig);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof ResolvedAddresses)) {
        return false;
      }
      ResolvedAddresses that = (ResolvedAddresses) obj;
      return Objects.equal(this.addresses, that.addresses)
          && Objects.equal(this.attributes, that.attributes)
          && Objects.equal(this.loadBalancingPolicyConfig, that.loadBalancingPolicyConfig);
    }
  }

  /**
   * Handles an error from the name resolution system.
   *
   * @param error a non-OK status
   * @since 1.2.0
   */
  public abstract void handleNameResolutionError(Status error);

  /**
   * Handles a state change on a Subchannel.
   *
   * <p>The initial state of a Subchannel is IDLE. You won't get a notification for the initial IDLE
   * state.
   *
   * <p>If the new state is not SHUTDOWN, this method should create a new picker and call {@link
   * Helper#updateBalancingState Helper.updateBalancingState()}.  Failing to do so may result in
   * unnecessary delays of RPCs. Please refer to {@link PickResult#withSubchannel
   * PickResult.withSubchannel()}'s javadoc for more information.
   *
   * <p>SHUTDOWN can only happen in two cases.  One is that LoadBalancer called {@link
   * Subchannel#shutdown} earlier, thus it should have already discarded this Subchannel.  The other
   * is that Channel is doing a {@link ManagedChannel#shutdownNow forced shutdown} or has already
   * terminated, thus there won't be further requests to LoadBalancer.  Therefore, the LoadBalancer
   * usually don't need to react to a SHUTDOWN state.
   *
   * @param subchannel the involved Subchannel
   * @param stateInfo the new state
   * @since 1.2.0
   * @deprecated This method will be removed.  Stop overriding it.  Instead, pass {@link
   *             SubchannelStateListener} to {@link Subchannel#start} to receive Subchannel state
   *             updates
   */
  @Deprecated
  public void handleSubchannelState(
      Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    // Do nothing.  If the implementation doesn't implement this, it will get subchannel states from
    // the new API.  We don't throw because there may be forwarding LoadBalancers still plumb this.
  }

  /**
   * The channel asks the load-balancer to shutdown.  No more methods on this class will be called
   * after this method.  The implementation should shutdown all Subchannels and OOB channels, and do
   * any other cleanup as necessary.
   *
   * @since 1.2.0
   */
  public abstract void shutdown();

  /**
   * Whether this LoadBalancer can handle empty address group list to be passed to {@link
   * #handleResolvedAddresses(ResolvedAddresses)}.  The default implementation returns
   * {@code false}, meaning that if the NameResolver returns an empty list, the Channel will turn
   * that into an error and call {@link #handleNameResolutionError}.  LoadBalancers that want to
   * accept empty lists should override this method and return {@code true}.
   *
   * <p>This method should always return a constant value.  It's not specified when this will be
   * called.
   */
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return false;
  }

  /**
   * The channel asks the LoadBalancer to establish connections now (if applicable) so that the
   * upcoming RPC may then just pick a ready connection without waiting for connections.  This
   * is triggered by {@link ManagedChannel#getState ManagedChannel.getState(true)}.
   *
   * <p>If LoadBalancer doesn't override it, this is no-op.  If it infeasible to create connections
   * given the current state, e.g. no Subchannel has been created yet, LoadBalancer can ignore this
   * request.
   *
   * @since 1.22.0
   */
  public void requestConnection() {}

  /**
   * The main balancing logic.  It <strong>must be thread-safe</strong>. Typically it should only
   * synchronize on its own state, and avoid synchronizing with the LoadBalancer's state.
   *
   * @since 1.2.0
   */
  @ThreadSafe
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class SubchannelPicker {
    /**
     * Make a balancing decision for a new RPC.
     *
     * @param args the pick arguments
     * @since 1.3.0
     */
    public abstract PickResult pickSubchannel(PickSubchannelArgs args);

    /**
     * Tries to establish connections now so that the upcoming RPC may then just pick a ready
     * connection without having to connect first.
     *
     * <p>No-op if unsupported.
     *
     * @deprecated override {@link LoadBalancer#requestConnection} instead.
     * @since 1.11.0
     */
    @Deprecated
    public void requestConnection() {}
  }

  /**
   * Provides arguments for a {@link SubchannelPicker#pickSubchannel(
   * LoadBalancer.PickSubchannelArgs)}.
   *
   * @since 1.2.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class PickSubchannelArgs {

    /**
     * Call options.
     *
     * @since 1.2.0
     */
    public abstract CallOptions getCallOptions();

    /**
     * Headers of the call. {@link SubchannelPicker#pickSubchannel} may mutate it before before
     * returning.
     *
     * @since 1.2.0
     */
    public abstract Metadata getHeaders();

    /**
     * Call method.
     *
     * @since 1.2.0
     */
    public abstract MethodDescriptor<?, ?> getMethodDescriptor();

    /**
     * Gets an object that can be informed about what sort of pick was made.
     */
    @Internal
    public PickDetailsConsumer getPickDetailsConsumer() {
      return new PickDetailsConsumer() {};
    }
  }

  /** Receives information about the pick being chosen. */
  @Internal
  public interface PickDetailsConsumer {
    /**
     * Optional labels that provide context of how the pick was routed. Particularly helpful for
     * per-RPC metrics.
     *
     * @throws NullPointerException if key or value is {@code null}
     */
    default void addOptionalLabel(String key, String value) {
      checkNotNull(key, "key");
      checkNotNull(value, "value");
    }
  }

  /**
   * A balancing decision made by {@link SubchannelPicker SubchannelPicker} for an RPC.
   *
   * <p>The outcome of the decision will be one of the following:
   * <ul>
   *   <li>Proceed: if a Subchannel is provided via {@link #withSubchannel withSubchannel()}, and is
   *       in READY state when the RPC tries to start on it, the RPC will proceed on that
   *       Subchannel.</li>
   *   <li>Error: if an error is provided via {@link #withError withError()}, and the RPC is not
   *       wait-for-ready (i.e., {@link CallOptions#withWaitForReady} was not called), the RPC will
   *       fail immediately with the given error.</li>
   *   <li>Buffer: in all other cases, the RPC will be buffered in the Channel, until the next
   *       picker is provided via {@link Helper#updateBalancingState Helper.updateBalancingState()},
   *       when the RPC will go through the same picking process again.</li>
   * </ul>
   *
   * @since 1.2.0
   */
  @Immutable
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public static final class PickResult {
    private static final PickResult NO_RESULT = new PickResult(null, null, Status.OK, false);

    @Nullable private final Subchannel subchannel;
    @Nullable private final ClientStreamTracer.Factory streamTracerFactory;
    // An error to be propagated to the application if subchannel == null
    // Or OK if there is no error.
    // subchannel being null and error being OK means RPC needs to wait
    private final Status status;
    // True if the result is created by withDrop()
    private final boolean drop;
    @Nullable private final String authorityOverride;

    private PickResult(
        @Nullable Subchannel subchannel, @Nullable ClientStreamTracer.Factory streamTracerFactory,
        Status status, boolean drop) {
      this.subchannel = subchannel;
      this.streamTracerFactory = streamTracerFactory;
      this.status = checkNotNull(status, "status");
      this.drop = drop;
      this.authorityOverride = null;
    }

    private PickResult(
        @Nullable Subchannel subchannel, @Nullable ClientStreamTracer.Factory streamTracerFactory,
        Status status, boolean drop, @Nullable String authorityOverride) {
      this.subchannel = subchannel;
      this.streamTracerFactory = streamTracerFactory;
      this.status = checkNotNull(status, "status");
      this.drop = drop;
      this.authorityOverride = authorityOverride;
    }

    /**
     * A decision to proceed the RPC on a Subchannel.
     *
     * <p>The Subchannel should either be an original Subchannel returned by {@link
     * Helper#createSubchannel Helper.createSubchannel()}, or a wrapper of it preferably based on
     * {@code ForwardingSubchannel}.  At the very least its {@link Subchannel#getInternalSubchannel
     * getInternalSubchannel()} must return the same object as the one returned by the original.
     * Otherwise the Channel cannot use it for the RPC.
     *
     * <p>When the RPC tries to use the return Subchannel, which is briefly after this method
     * returns, the state of the Subchannel will decide where the RPC would go:
     *
     * <ul>
     *   <li>READY: the RPC will proceed on this Subchannel.</li>
     *   <li>IDLE: the RPC will be buffered.  Subchannel will attempt to create connection.</li>
     *   <li>All other states: the RPC will be buffered.</li>
     * </ul>
     *
     * <p><strong>All buffered RPCs will stay buffered</strong> until the next call of {@link
     * Helper#updateBalancingState Helper.updateBalancingState()}, which will trigger a new picking
     * process.
     *
     * <p>Note that Subchannel's state may change at the same time the picker is making the
     * decision, which means the decision may be made with (to-be) outdated information.  For
     * example, a picker may return a Subchannel known to be READY, but it has become IDLE when is
     * about to be used by the RPC, which makes the RPC to be buffered.  The LoadBalancer will soon
     * learn about the Subchannels' transition from READY to IDLE, create a new picker and allow the
     * RPC to use another READY transport if there is any.
     *
     * <p>You will want to avoid running into a situation where there are READY Subchannels out
     * there but some RPCs are still buffered for longer than a brief time.
     * <ul>
     *   <li>This can happen if you return Subchannels with states other than READY and IDLE.  For
     *       example, suppose you round-robin on 2 Subchannels, in READY and CONNECTING states
     *       respectively.  If the picker ignores the state and pick them equally, 50% of RPCs will
     *       be stuck in buffered state until both Subchannels are READY.</li>
     *   <li>This can also happen if you don't create a new picker at key state changes of
     *       Subchannels.  Take the above round-robin example again.  Suppose you do pick only READY
     *       and IDLE Subchannels, and initially both Subchannels are READY.  Now one becomes IDLE,
     *       then CONNECTING and stays CONNECTING for a long time.  If you don't create a new picker
     *       in response to the CONNECTING state to exclude that Subchannel, 50% of RPCs will hit it
     *       and be buffered even though the other Subchannel is READY.</li>
     * </ul>
     *
     * <p>In order to prevent unnecessary delay of RPCs, the rules of thumb are:
     * <ol>
     *   <li>The picker should only pick Subchannels that are known as READY or IDLE.  Whether to
     *       pick IDLE Subchannels depends on whether you want Subchannels to connect on-demand or
     *       actively:
     *       <ul>
     *         <li>If you want connect-on-demand, include IDLE Subchannels in your pick results,
     *             because when an RPC tries to use an IDLE Subchannel, the Subchannel will try to
     *             connect.</li>
     *         <li>If you want Subchannels to be always connected even when there is no RPC, you
     *             would call {@link Subchannel#requestConnection Subchannel.requestConnection()}
     *             whenever the Subchannel has transitioned to IDLE, then you don't need to include
     *             IDLE Subchannels in your pick results.</li>
     *       </ul></li>
     *   <li>Always create a new picker and call {@link Helper#updateBalancingState
     *       Helper.updateBalancingState()} whenever {@link #handleSubchannelState
     *       handleSubchannelState()} is called, unless the new state is SHUTDOWN. See
     *       {@code handleSubchannelState}'s javadoc for more details.</li>
     * </ol>
     *
     * @param subchannel the picked Subchannel.  It must have been {@link Subchannel#start started}
     * @param streamTracerFactory if not null, will be used to trace the activities of the stream
     *                            created as a result of this pick. Note it's possible that no
     *                            stream is created at all in some cases.
     * @since 1.3.0
     */
    public static PickResult withSubchannel(
        Subchannel subchannel, @Nullable ClientStreamTracer.Factory streamTracerFactory) {
      return new PickResult(
          checkNotNull(subchannel, "subchannel"), streamTracerFactory, Status.OK,
          false);
    }

    /**
     * Same as {@code withSubchannel(subchannel, streamTracerFactory)} but with an authority name
     * to override in the host header.
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/11656")
    public static PickResult withSubchannel(
        Subchannel subchannel, @Nullable ClientStreamTracer.Factory streamTracerFactory,
        @Nullable String authorityOverride) {
      return new PickResult(
          checkNotNull(subchannel, "subchannel"), streamTracerFactory, Status.OK,
          false, authorityOverride);
    }

    /**
     * Equivalent to {@code withSubchannel(subchannel, null)}.
     *
     * @since 1.2.0
     */
    public static PickResult withSubchannel(Subchannel subchannel) {
      return withSubchannel(subchannel, null);
    }

    /**
     * A decision to report a connectivity error to the RPC.  If the RPC is {@link
     * CallOptions#withWaitForReady wait-for-ready}, it will stay buffered.  Otherwise, it will fail
     * with the given error.
     *
     * @param error the error status.  Must not be OK.
     * @since 1.2.0
     */
    public static PickResult withError(Status error) {
      Preconditions.checkArgument(!error.isOk(), "error status shouldn't be OK");
      return new PickResult(null, null, error, false);
    }

    /**
     * A decision to fail an RPC immediately.  This is a final decision and will ignore retry
     * policy.
     *
     * @param status the status with which the RPC will fail.  Must not be OK.
     * @since 1.8.0
     */
    public static PickResult withDrop(Status status) {
      Preconditions.checkArgument(!status.isOk(), "drop status shouldn't be OK");
      return new PickResult(null, null, status, true);
    }

    /**
     * No decision could be made.  The RPC will stay buffered.
     *
     * @since 1.2.0
     */
    public static PickResult withNoResult() {
      return NO_RESULT;
    }

    /** Returns the authority override if any. */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/11656")
    @Nullable
    public String getAuthorityOverride() {
      return authorityOverride;
    }

    /**
     * The Subchannel if this result was created by {@link #withSubchannel withSubchannel()}, or
     * null otherwise.
     *
     * @since 1.2.0
     */
    @Nullable
    public Subchannel getSubchannel() {
      return subchannel;
    }

    /**
     * The stream tracer factory this result was created with.
     *
     * @since 1.3.0
     */
    @Nullable
    public ClientStreamTracer.Factory getStreamTracerFactory() {
      return streamTracerFactory;
    }

    /**
     * The status associated with this result.  Non-{@code OK} if created with {@link #withError
     * withError}, or {@code OK} otherwise.
     *
     * @since 1.2.0
     */
    public Status getStatus() {
      return status;
    }

    /**
     * Returns {@code true} if this result was created by {@link #withDrop withDrop()}.
     *
     * @since 1.8.0
     */
    public boolean isDrop() {
      return drop;
    }

    /**
     * Returns {@code true} if the pick was not created with {@link #withNoResult()}.
     */
    public boolean hasResult() {
      return !(subchannel == null && status.isOk());
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("subchannel", subchannel)
          .add("streamTracerFactory", streamTracerFactory)
          .add("status", status)
          .add("drop", drop)
          .add("authority-override", authorityOverride)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(subchannel, status, streamTracerFactory, drop);
    }

    /**
     * Returns true if the {@link Subchannel}, {@link Status}, and
     * {@link ClientStreamTracer.Factory} all match.
     */
    @Override
    public boolean equals(Object other) {
      if (!(other instanceof PickResult)) {
        return false;
      }
      PickResult that = (PickResult) other;
      return Objects.equal(subchannel, that.subchannel) && Objects.equal(status, that.status)
          && Objects.equal(streamTracerFactory, that.streamTracerFactory)
          && drop == that.drop;
    }
  }

  /**
   * Arguments for creating a {@link Subchannel}.
   *
   * @since 1.22.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public static final class CreateSubchannelArgs {
    private final List<EquivalentAddressGroup> addrs;
    private final Attributes attrs;
    private final Object[][] customOptions;

    private CreateSubchannelArgs(
        List<EquivalentAddressGroup> addrs, Attributes attrs, Object[][] customOptions) {
      this.addrs = checkNotNull(addrs, "addresses are not set");
      this.attrs = checkNotNull(attrs, "attrs");
      this.customOptions = checkNotNull(customOptions, "customOptions");
    }

    /**
     * Returns the addresses, which is an unmodifiable list.
     */
    public List<EquivalentAddressGroup> getAddresses() {
      return addrs;
    }

    /**
     * Returns the attributes.
     */
    public Attributes getAttributes() {
      return attrs;
    }

    /**
     * Get the value for a custom option or its inherent default.
     *
     * @param key Key identifying option
     */
    @SuppressWarnings("unchecked")
    public <T> T getOption(Key<T> key) {
      Preconditions.checkNotNull(key, "key");
      for (int i = 0; i < customOptions.length; i++) {
        if (key.equals(customOptions[i][0])) {
          return (T) customOptions[i][1];
        }
      }
      return key.defaultValue;
    }

    /**
     * Returns a builder with the same initial values as this object.
     */
    public Builder toBuilder() {
      return newBuilder().setAddresses(addrs).setAttributes(attrs).copyCustomOptions(customOptions);
    }

    /**
     * Creates a new builder.
     */
    public static Builder newBuilder() {
      return new Builder();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("addrs", addrs)
          .add("attrs", attrs)
          .add("customOptions", Arrays.deepToString(customOptions))
          .toString();
    }

    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
    public static final class Builder {

      private List<EquivalentAddressGroup> addrs;
      private Attributes attrs = Attributes.EMPTY;
      private Object[][] customOptions = new Object[0][2];

      Builder() {
      }

      private Builder copyCustomOptions(Object[][] options) {
        customOptions = new Object[options.length][2];
        System.arraycopy(options, 0, customOptions, 0, options.length);
        return this;
      }

      /**
       * Add a custom option. Any existing value for the key is overwritten.
       *
       * <p>This is an <strong>optional</strong> property.
       *
       * @param key the option key
       * @param value the option value
       */
      public <T> Builder addOption(Key<T> key, T value) {
        Preconditions.checkNotNull(key, "key");
        Preconditions.checkNotNull(value, "value");

        int existingIdx = -1;
        for (int i = 0; i < customOptions.length; i++) {
          if (key.equals(customOptions[i][0])) {
            existingIdx = i;
            break;
          }
        }

        if (existingIdx == -1) {
          Object[][] newCustomOptions = new Object[customOptions.length + 1][2];
          System.arraycopy(customOptions, 0, newCustomOptions, 0, customOptions.length);
          customOptions = newCustomOptions;
          existingIdx = customOptions.length - 1;
        }
        customOptions[existingIdx] = new Object[]{key, value};
        return this;
      }

      /**
       * The addresses to connect to.  All addresses are considered equivalent and will be tried
       * in the order they are provided.
       */
      public Builder setAddresses(EquivalentAddressGroup addrs) {
        this.addrs = Collections.singletonList(addrs);
        return this;
      }

      /**
       * The addresses to connect to.  All addresses are considered equivalent and will
       * be tried in the order they are provided.
       *
       * <p>This is a <strong>required</strong> property.
       *
       * @throws IllegalArgumentException if {@code addrs} is empty
       */
      public Builder setAddresses(List<EquivalentAddressGroup> addrs) {
        checkArgument(!addrs.isEmpty(), "addrs is empty");
        this.addrs = Collections.unmodifiableList(new ArrayList<>(addrs));
        return this;
      }

      /**
       * Attributes provided here will be included in {@link Subchannel#getAttributes}.
       *
       * <p>This is an <strong>optional</strong> property.  Default is empty if not set.
       */
      public Builder setAttributes(Attributes attrs) {
        this.attrs = checkNotNull(attrs, "attrs");
        return this;
      }

      /**
       * Creates a new args object.
       */
      public CreateSubchannelArgs build() {
        return new CreateSubchannelArgs(addrs, attrs, customOptions);
      }
    }

    /**
     * Key for a key-value pair. Uses reference equality.
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
    public static final class Key<T> {

      private final String debugString;
      private final T defaultValue;

      private Key(String debugString, T defaultValue) {
        this.debugString = debugString;
        this.defaultValue = defaultValue;
      }

      /**
       * Factory method for creating instances of {@link Key}. The default value of the key is
       * {@code null}.
       *
       * @param debugString a debug string that describes this key.
       * @param <T> Key type
       * @return Key object
       */
      public static <T> Key<T> create(String debugString) {
        Preconditions.checkNotNull(debugString, "debugString");
        return new Key<>(debugString, /*defaultValue=*/ null);
      }

      /**
       * Factory method for creating instances of {@link Key}.
       *
       * @param debugString a debug string that describes this key.
       * @param defaultValue default value to return when value for key not set
       * @param <T> Key type
       * @return Key object
       */
      public static <T> Key<T> createWithDefault(String debugString, T defaultValue) {
        Preconditions.checkNotNull(debugString, "debugString");
        return new Key<>(debugString, defaultValue);
      }

      /**
       * Returns the user supplied default value for this key.
       */
      public T getDefault() {
        return defaultValue;
      }

      @Override
      public String toString() {
        return debugString;
      }
    }
  }

  /**
   * Provides essentials for LoadBalancer implementations.
   *
   * @since 1.2.0
   */
  @ThreadSafe
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class Helper {
    /**
     * Creates a Subchannel, which is a logical connection to the given group of addresses which are
     * considered equivalent.  The {@code attrs} are custom attributes associated with this
     * Subchannel, and can be accessed later through {@link Subchannel#getAttributes
     * Subchannel.getAttributes()}.
     *
     * <p>The LoadBalancer is responsible for closing unused Subchannels, and closing all
     * Subchannels within {@link #shutdown}.
     *
     * <p>It must be called from {@link #getSynchronizationContext the Synchronization Context}
     *
     * @return Must return a valid Subchannel object, may not return null.
     *
     * @since 1.22.0
     */
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      throw new UnsupportedOperationException();
    }

    /**
     * Create an out-of-band channel for the LoadBalancer’s own RPC needs, e.g., talking to an
     * external load-balancer service.
     *
     * <p>The LoadBalancer is responsible for closing unused OOB channels, and closing all OOB
     * channels within {@link #shutdown}.
     *
     * @since 1.4.0
     */
    public abstract ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority);

    /**
     * Create an out-of-band channel for the LoadBalancer's own RPC needs, e.g., talking to an
     * external load-balancer service. This version of the method allows multiple EAGs, so different
     * addresses can have different authorities.
     *
     * <p>The LoadBalancer is responsible for closing unused OOB channels, and closing all OOB
     * channels within {@link #shutdown}.
     * */
    public ManagedChannel createOobChannel(List<EquivalentAddressGroup> eag,
        String authority) {
      throw new UnsupportedOperationException();
    }

    /**
     * Updates the addresses used for connections in the {@code Channel} that was created by {@link
     * #createOobChannel(EquivalentAddressGroup, String)}. This is superior to {@link
     * #createOobChannel(EquivalentAddressGroup, String)} when the old and new addresses overlap,
     * since the channel can continue using an existing connection.
     *
     * @throws IllegalArgumentException if {@code channel} was not returned from {@link
     *     #createOobChannel}
     * @since 1.4.0
     */
    public void updateOobChannelAddresses(ManagedChannel channel, EquivalentAddressGroup eag) {
      throw new UnsupportedOperationException();
    }

    /**
     * Updates the addresses with a new EAG list. Connection is continued when old and new addresses
     * overlap.
     * */
    public void updateOobChannelAddresses(ManagedChannel channel,
        List<EquivalentAddressGroup> eag) {
      throw new UnsupportedOperationException();
    }

    /**
     * Creates an out-of-band channel for LoadBalancer's own RPC needs, e.g., talking to an external
     * load-balancer service, that is specified by a target string.  See the documentation on
     * {@link ManagedChannelBuilder#forTarget} for the format of a target string.
     *
     * <p>The target string will be resolved by a {@link NameResolver} created according to the
     * target string.
     *
     * <p>The LoadBalancer is responsible for closing unused OOB channels, and closing all OOB
     * channels within {@link #shutdown}.
     *
     * @since 1.20.0
     */
    public ManagedChannel createResolvingOobChannel(String target) {
      return createResolvingOobChannelBuilder(target).build();
    }

    /**
     * Creates an out-of-band channel builder for LoadBalancer's own RPC needs, e.g., talking to an
     * external load-balancer service, that is specified by a target string.  See the documentation
     * on {@link ManagedChannelBuilder#forTarget} for the format of a target string.
     *
     * <p>The target string will be resolved by a {@link NameResolver} created according to the
     * target string.
     *
     * <p>The returned oob-channel builder defaults to use the same authority and ChannelCredentials
     * (without bearer tokens) as the parent channel's for authentication. This is different from
     * {@link #createResolvingOobChannelBuilder(String, ChannelCredentials)}.
     *
     * <p>The LoadBalancer is responsible for closing unused OOB channels, and closing all OOB
     * channels within {@link #shutdown}.
     *
     * @deprecated Use {@link #createResolvingOobChannelBuilder(String, ChannelCredentials)}
     *     instead.
     * @since 1.31.0
     */
    @Deprecated
    public ManagedChannelBuilder<?> createResolvingOobChannelBuilder(String target) {
      throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Creates an out-of-band channel builder for LoadBalancer's own RPC needs, e.g., talking to an
     * external load-balancer service, that is specified by a target string and credentials.  See
     * the documentation on {@link Grpc#newChannelBuilder} for the format of a target string.
     *
     * <p>The target string will be resolved by a {@link NameResolver} created according to the
     * target string.
     *
     * <p>The LoadBalancer is responsible for closing unused OOB channels, and closing all OOB
     * channels within {@link #shutdown}.
     *
     * @since 1.35.0
     */
    public ManagedChannelBuilder<?> createResolvingOobChannelBuilder(
        String target, ChannelCredentials creds) {
      throw new UnsupportedOperationException();
    }

    /**
     * Set a new state with a new picker to the channel.
     *
     * <p>When a new picker is provided via {@code updateBalancingState()}, the channel will apply
     * the picker on all buffered RPCs, by calling {@link SubchannelPicker#pickSubchannel(
     * LoadBalancer.PickSubchannelArgs)}.
     *
     * <p>The channel will hold the picker and use it for all RPCs, until {@code
     * updateBalancingState()} is called again and a new picker replaces the old one.  If {@code
     * updateBalancingState()} has never been called, the channel will buffer all RPCs until a
     * picker is provided.
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     *
     * <p>The passed state will be the channel's new state. The SHUTDOWN state should not be passed
     * and its behavior is undefined.
     *
     * @since 1.6.0
     */
    public abstract void updateBalancingState(
        @Nonnull ConnectivityState newState, @Nonnull SubchannelPicker newPicker);

    /**
     * Call {@link NameResolver#refresh} on the channel's resolver.
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     *
     * @since 1.18.0
     */
    public void refreshNameResolution() {
      throw new UnsupportedOperationException();
    }

    /**
     * Historically the channel automatically refreshes name resolution if any subchannel
     * connection is broken. It's transitioning to let load balancers make the decision. To
     * avoid silent breakages, the channel checks if {@link #refreshNameResolution} is called
     * by the load balancer. If not, it will do it and log a warning. This will be removed in
     * the future and load balancers are completely responsible for triggering the refresh.
     * See <a href="https://github.com/grpc/grpc-java/issues/8088">#8088</a> for the background.
     *
     * <p>This should rarely be used, but sometimes the address for the subchannel wasn't
     * provided by the name resolver and a refresh needs to be directed somewhere else instead.
     * Then you can call this method to disable the short-tem check for detecting LoadBalancers
     * that need to be updated for the new expected behavior.
     *
     * @since 1.38.0
     * @deprecated Warning has been removed
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8088")
    @Deprecated
    public void ignoreRefreshNameResolutionCheck() {
      // no-op
    }

    /**
     * Returns a {@link SynchronizationContext} that runs tasks in the same Synchronization Context
     * as that the callback methods on the {@link LoadBalancer} interface are run in.
     *
     * <p>Pro-tip: in order to call {@link SynchronizationContext#schedule}, you need to provide a
     * {@link ScheduledExecutorService}.  {@link #getScheduledExecutorService} is provided for your
     * convenience.
     *
     * @since 1.17.0
     */
    public SynchronizationContext getSynchronizationContext() {
      // TODO(zhangkun): make getSynchronizationContext() abstract after runSerialized() is deleted
      throw new UnsupportedOperationException();
    }

    /**
     * Returns a {@link ScheduledExecutorService} for scheduling delayed tasks.
     *
     * <p>This service is a shared resource and is only meant for quick tasks.  DO NOT block or run
     * time-consuming tasks.
     *
     * <p>The returned service doesn't support {@link ScheduledExecutorService#shutdown shutdown()}
     * and {@link ScheduledExecutorService#shutdownNow shutdownNow()}.  They will throw if called.
     *
     * @since 1.17.0
     */
    public ScheduledExecutorService getScheduledExecutorService() {
      throw new UnsupportedOperationException();
    }

    /**
     * Returns the authority string of the channel, which is derived from the DNS-style target name.
     * If overridden by a load balancer, {@link #getUnsafeChannelCredentials} must also be
     * overridden to call {@link #getChannelCredentials} or provide appropriate credentials.
     *
     * @since 1.2.0
     */
    public abstract String getAuthority();

    /**
     * Returns the target string of the channel, guaranteed to include its scheme.
     */
    public String getChannelTarget() {
      throw new UnsupportedOperationException();
    }

    /**
     * Returns the ChannelCredentials used to construct the channel, without bearer tokens.
     *
     * @since 1.35.0
     */
    public ChannelCredentials getChannelCredentials() {
      return getUnsafeChannelCredentials().withoutBearerTokens();
    }

    /**
     * Returns the UNSAFE ChannelCredentials used to construct the channel,
     * including bearer tokens. Load balancers should generally have no use for
     * these credentials and use of them is heavily discouraged. These must be used
     * <em>very</em> carefully to avoid sending bearer tokens to untrusted servers
     * as the server could then impersonate the client. Generally it is only safe
     * to use these credentials when communicating with the backend.
     *
     * @since 1.35.0
     */
    public ChannelCredentials getUnsafeChannelCredentials() {
      throw new UnsupportedOperationException();
    }

    /**
     * Returns the {@link ChannelLogger} for the Channel served by this LoadBalancer.
     *
     * @since 1.17.0
     */
    public ChannelLogger getChannelLogger() {
      throw new UnsupportedOperationException();
    }

    /**
     * Returns the {@link NameResolver.Args} that the Channel uses to create {@link NameResolver}s.
     *
     * @since 1.22.0
     */
    public NameResolver.Args getNameResolverArgs() {
      throw new UnsupportedOperationException();
    }

    /**
     * Returns the {@link NameResolverRegistry} that the Channel uses to look for {@link
     * NameResolver}s.
     *
     * @since 1.22.0
     */
    public NameResolverRegistry getNameResolverRegistry() {
      throw new UnsupportedOperationException();
    }

    /**
     * Returns the {@link MetricRecorder} that the channel uses to record metrics.
     *
     * @since 1.64.0
     */
    @Internal
    public MetricRecorder getMetricRecorder() {
      return new MetricRecorder() {};
    }
  }

  /**
   * A logical connection to a server, or a group of equivalent servers represented by an {@link 
   * EquivalentAddressGroup}.
   *
   * <p>It maintains at most one physical connection (aka transport) for sending new RPCs, while
   * also keeps track of previous transports that has been shut down but not terminated yet.
   *
   * <p>If there isn't an active transport yet, and an RPC is assigned to the Subchannel, it will
   * create a new transport.  It won't actively create transports otherwise.  {@link
   * #requestConnection requestConnection()} can be used to ask Subchannel to create a transport if
   * there isn't any.
   *
   * <p>{@link #start} must be called prior to calling any other methods, with the exception of
   * {@link #shutdown}, which can be called at any time.
   *
   * @since 1.2.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class Subchannel {
    /**
     * Starts the Subchannel.  Can only be called once.
     *
     * <p>Must be called prior to any other method on this class, except for {@link #shutdown} which
     * may be called at any time.
     *
     * <p>Must be called from the {@link Helper#getSynchronizationContext Synchronization Context},
     * otherwise it may throw.  See <a href="https://github.com/grpc/grpc-java/issues/5015">
     * #5015</a> for more discussions.
     *
     * @param listener receives state updates for this Subchannel.
     */
    public void start(SubchannelStateListener listener) {
      throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Shuts down the Subchannel.  After this method is called, this Subchannel should no longer
     * be returned by the latest {@link SubchannelPicker picker}, and can be safely discarded.
     *
     * <p>Calling it on an already shut-down Subchannel has no effect.
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     *
     * @since 1.2.0
     */
    public abstract void shutdown();

    /**
     * Asks the Subchannel to create a connection (aka transport), if there isn't an active one.
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     *
     * @since 1.2.0
     */
    public abstract void requestConnection();

    /**
     * Returns the addresses that this Subchannel is bound to.  This can be called only if
     * the Subchannel has only one {@link EquivalentAddressGroup}.  Under the hood it calls
     * {@link #getAllAddresses}.
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     *
     * @throws IllegalStateException if this subchannel has more than one EquivalentAddressGroup.
     *         Use {@link #getAllAddresses} instead
     * @since 1.2.0
     */
    public final EquivalentAddressGroup getAddresses() {
      List<EquivalentAddressGroup> groups = getAllAddresses();
      Preconditions.checkState(groups != null && groups.size() == 1,
          "%s does not have exactly one group", groups);
      return groups.get(0);
    }

    /**
     * Returns the addresses that this Subchannel is bound to. The returned list will not be empty.
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     *
     * @since 1.14.0
     */
    public List<EquivalentAddressGroup> getAllAddresses() {
      throw new UnsupportedOperationException();
    }

    /**
     * The same attributes passed to {@link Helper#createSubchannel Helper.createSubchannel()}.
     * LoadBalancer can use it to attach additional information here, e.g., the shard this
     * Subchannel belongs to.
     *
     * @since 1.2.0
     */
    public abstract Attributes getAttributes();

    /**
     * (Internal use only) returns a {@link Channel} that is backed by this Subchannel.  This allows
     * a LoadBalancer to issue its own RPCs for auxiliary purposes, such as health-checking, on
     * already-established connections.  This channel has certain restrictions:
     * <ol>
     *   <li>It can issue RPCs only if the Subchannel is {@code READY}. If {@link
     *   Channel#newCall} is called when the Subchannel is not {@code READY}, the RPC will fail
     *   immediately.</li>
     *   <li>It doesn't support {@link CallOptions#withWaitForReady wait-for-ready} RPCs. Such RPCs
     *   will fail immediately.</li>
     * </ol>
     *
     * <p>RPCs made on this Channel is not counted when determining ManagedChannel's {@link
     * ManagedChannelBuilder#idleTimeout idle mode}.  In other words, they won't prevent
     * ManagedChannel from entering idle mode.
     *
     * <p>Warning: RPCs made on this channel will prevent a shut-down transport from terminating. If
     * you make long-running RPCs, you need to make sure they will finish in time after the
     * Subchannel has transitioned away from {@code READY} state
     * (notified through {@link #handleSubchannelState}).
     *
     * <p>Warning: this is INTERNAL API, is not supposed to be used by external users, and may
     * change without notice. If you think you must use it, please file an issue.
     */
    @Internal
    public Channel asChannel() {
      throw new UnsupportedOperationException();
    }

    /**
     * Returns a {@link ChannelLogger} for this Subchannel.
     *
     * @since 1.17.0
     */
    public ChannelLogger getChannelLogger() {
      throw new UnsupportedOperationException();
    }

    /**
     * Replaces the existing addresses used with this {@code Subchannel}. If the new and old
     * addresses overlap, the Subchannel can continue using an existing connection.
     *
     * <p>It must be called from the Synchronization Context or will throw.
     *
     * @throws IllegalArgumentException if {@code addrs} is empty
     * @since 1.22.0
     */
    public void updateAddresses(List<EquivalentAddressGroup> addrs) {
      throw new UnsupportedOperationException();
    }

    /**
     * (Internal use only) returns an object that represents the underlying subchannel that is used
     * by the Channel for sending RPCs when this {@link Subchannel} is picked.  This is an opaque
     * object that is both provided and consumed by the Channel.  Its type <strong>is not</strong>
     * {@code Subchannel}.
     *
     * <p>Warning: this is INTERNAL API, is not supposed to be used by external users, and may
     * change without notice. If you think you must use it, please file an issue and we can consider
     * removing its "internal" status.
     */
    @Internal
    public Object getInternalSubchannel() {
      throw new UnsupportedOperationException();
    }

    /**
     * (Internal use only) returns attributes of the address subchannel is connected to.
     *
     * <p>Warning: this is INTERNAL API, is not supposed to be used by external users, and may
     * change without notice. If you think you must use it, please file an issue and we can consider
     * removing its "internal" status.
     */
    @Internal
    public Attributes getConnectedAddressAttributes() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Receives state changes for one {@link Subchannel}. All methods are run under {@link
   * Helper#getSynchronizationContext}.
   *
   * @since 1.22.0
   */
  public interface SubchannelStateListener {
    /**
     * Handles a state change on a Subchannel.
     *
     * <p>The initial state of a Subchannel is IDLE. You won't get a notification for the initial
     * IDLE state.
     *
     * <p>If the new state is not SHUTDOWN, this method should create a new picker and call {@link
     * Helper#updateBalancingState Helper.updateBalancingState()}.  Failing to do so may result in
     * unnecessary delays of RPCs. Please refer to {@link PickResult#withSubchannel
     * PickResult.withSubchannel()}'s javadoc for more information.
     *
     * <p>When a subchannel's state is IDLE or TRANSIENT_FAILURE and the address for the subchannel
     * was received in {@link LoadBalancer#handleResolvedAddresses}, load balancers should call
     * {@link Helper#refreshNameResolution} to inform polling name resolvers that it is an
     * appropriate time to refresh the addresses. Without the refresh, changes to the addresses may
     * never be detected.
     *
     * <p>SHUTDOWN can only happen in two cases.  One is that LoadBalancer called {@link
     * Subchannel#shutdown} earlier, thus it should have already discarded this Subchannel.  The
     * other is that Channel is doing a {@link ManagedChannel#shutdownNow forced shutdown} or has
     * already terminated, thus there won't be further requests to LoadBalancer.  Therefore, the
     * LoadBalancer usually don't need to react to a SHUTDOWN state.
     *
     * @param newState the new state
     * @since 1.22.0
     */
    void onSubchannelState(ConnectivityStateInfo newState);
  }

  /**
   * Factory to create {@link LoadBalancer} instance.
   *
   * @since 1.2.0
   */
  @ThreadSafe
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class Factory {
    /**
     * Creates a {@link LoadBalancer} that will be used inside a channel.
     *
     * @since 1.2.0
     */
    public abstract LoadBalancer newLoadBalancer(Helper helper);
  }

  /**
   * A picker that always returns an erring pick.
   *
   * @deprecated Use {@code new FixedResultPicker(PickResult.withError(error))} instead.
   */
  @Deprecated
  public static final class ErrorPicker extends SubchannelPicker {

    private final Status error;

    public ErrorPicker(Status error) {
      this.error = checkNotNull(error, "error");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return PickResult.withError(error);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("error", error)
          .toString();
    }
  }

  /** A picker that always returns the same result. */
  public static final class FixedResultPicker extends SubchannelPicker {
    private final PickResult result;

    public FixedResultPicker(PickResult result) {
      this.result = Preconditions.checkNotNull(result, "result");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return result;
    }

    @Override
    public String toString() {
      return "FixedResultPicker(" + result + ")";
    }

    @Override
    public int hashCode() {
      return result.hashCode();
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof FixedResultPicker)) {
        return false;
      }
      FixedResultPicker that = (FixedResultPicker) o;
      return this.result.equals(that.result);
    }
  }
}
