/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.internal;

import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.ForOverride;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.HttpConnectProxiedSocketAddress;
import io.grpc.InternalChannelz;
import io.grpc.InternalChannelz.ChannelStats;
import io.grpc.InternalInstrumented;
import io.grpc.InternalLogId;
import io.grpc.InternalWithLogId;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Transports for a single {@link SocketAddress}.
 */
@ThreadSafe
final class InternalSubchannel implements InternalInstrumented<ChannelStats>, TransportProvider {

  private final InternalLogId logId;
  private final String authority;
  private final String userAgent;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Callback callback;
  private final ClientTransportFactory transportFactory;
  private final ScheduledExecutorService scheduledExecutor;
  private final InternalChannelz channelz;
  private final CallTracer callsTracer;
  private final ChannelTracer channelTracer;
  private final ChannelLogger channelLogger;

  /**
   * All field must be mutated in the syncContext.
   */
  private final SynchronizationContext syncContext;

  /**
   * The index of the address corresponding to pendingTransport/activeTransport, or at beginning if
   * both are null.
   *
   * <p>Note: any {@link Index#updateAddresses(List)} should also update {@link #addressGroups}.
   */
  private final Index addressIndex;

  /**
   * A volatile accessor to {@link Index#getAddressGroups()}. There are few methods ({@link
   * #getAddressGroups()} and {@link #toString()} access this value where they supposed to access
   * in the {@link #syncContext}. Ideally {@link Index#getAddressGroups()} can be volatile, so we
   * don't need to maintain this volatile accessor. Although, having this accessor can reduce
   * unnecessary volatile reads while it delivers clearer intention of why .
   */
  private volatile List<EquivalentAddressGroup> addressGroups;

  /**
   * The policy to control back off between reconnects. Non-{@code null} when a reconnect task is
   * scheduled.
   */
  private BackoffPolicy reconnectPolicy;

  /**
   * Timer monitoring duration since entering CONNECTING state.
   */
  private final Stopwatch connectingTimer;

  @Nullable
  private ScheduledHandle reconnectTask;

  /**
   * All transports that are not terminated. At the very least the value of {@link #activeTransport}
   * will be present, but previously used transports that still have streams or are stopping may
   * also be present.
   */
  private final Collection<ConnectionClientTransport> transports = new ArrayList<>();

  // Must only be used from syncContext
  private final InUseStateAggregator<ConnectionClientTransport> inUseStateAggregator =
      new InUseStateAggregator<ConnectionClientTransport>() {
        @Override
        protected void handleInUse() {
          callback.onInUse(InternalSubchannel.this);
        }

        @Override
        protected void handleNotInUse() {
          callback.onNotInUse(InternalSubchannel.this);
        }
      };

  /**
   * The to-be active transport, which is not ready yet.
   */
  @Nullable
  private ConnectionClientTransport pendingTransport;

  /**
   * The transport for new outgoing requests. Non-null only in READY state.
   */
  @Nullable
  private volatile ManagedClientTransport activeTransport;

  private volatile ConnectivityStateInfo state = ConnectivityStateInfo.forNonError(IDLE);

  private Status shutdownReason;

  InternalSubchannel(List<EquivalentAddressGroup> addressGroups, String authority, String userAgent,
      BackoffPolicy.Provider backoffPolicyProvider,
      ClientTransportFactory transportFactory, ScheduledExecutorService scheduledExecutor,
      Supplier<Stopwatch> stopwatchSupplier, SynchronizationContext syncContext, Callback callback,
      InternalChannelz channelz, CallTracer callsTracer, ChannelTracer channelTracer,
      InternalLogId logId, ChannelLogger channelLogger) {
    Preconditions.checkNotNull(addressGroups, "addressGroups");
    Preconditions.checkArgument(!addressGroups.isEmpty(), "addressGroups is empty");
    checkListHasNoNulls(addressGroups, "addressGroups contains null entry");
    List<EquivalentAddressGroup> unmodifiableAddressGroups =
        Collections.unmodifiableList(new ArrayList<>(addressGroups));
    this.addressGroups = unmodifiableAddressGroups;
    this.addressIndex = new Index(unmodifiableAddressGroups);
    this.authority = authority;
    this.userAgent = userAgent;
    this.backoffPolicyProvider = backoffPolicyProvider;
    this.transportFactory = transportFactory;
    this.scheduledExecutor = scheduledExecutor;
    this.connectingTimer = stopwatchSupplier.get();
    this.syncContext = syncContext;
    this.callback = callback;
    this.channelz = channelz;
    this.callsTracer = callsTracer;
    this.channelTracer = Preconditions.checkNotNull(channelTracer, "channelTracer");
    this.logId = Preconditions.checkNotNull(logId, "logId");
    this.channelLogger = Preconditions.checkNotNull(channelLogger, "channelLogger");
  }

  ChannelLogger getChannelLogger() {
    return channelLogger;
  }

  @Override
  public ClientTransport obtainActiveTransport() {
    ClientTransport savedTransport = activeTransport;
    if (savedTransport != null) {
      return savedTransport;
    }
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        if (state.getState() == IDLE) {
          channelLogger.log(ChannelLogLevel.INFO, "CONNECTING as requested");
          gotoNonErrorState(CONNECTING);
          startNewTransport();
        }
      }
    });
    return null;
  }

  /**
   * Returns a READY transport if there is any, without trying to connect.
   */
  @Nullable
  ClientTransport getTransport() {
    return activeTransport;
  }

  /**
   * Returns the authority string associated with this Subchannel.
   */
  String getAuthority() {
    return authority;
  }

  private void startNewTransport() {
    syncContext.throwIfNotInThisSynchronizationContext();

    Preconditions.checkState(reconnectTask == null, "Should have no reconnectTask scheduled");

    if (addressIndex.isAtBeginning()) {
      connectingTimer.reset().start();
    }
    SocketAddress address = addressIndex.getCurrentAddress();

    HttpConnectProxiedSocketAddress proxiedAddr = null;
    if (address instanceof HttpConnectProxiedSocketAddress) {
      proxiedAddr = (HttpConnectProxiedSocketAddress) address;
      address = proxiedAddr.getTargetAddress();
    }

    Attributes currentEagAttributes = addressIndex.getCurrentEagAttributes();
    String eagChannelAuthority = currentEagAttributes
            .get(EquivalentAddressGroup.ATTR_AUTHORITY_OVERRIDE);
    ClientTransportFactory.ClientTransportOptions options =
        new ClientTransportFactory.ClientTransportOptions()
          .setAuthority(eagChannelAuthority != null ? eagChannelAuthority : authority)
          .setEagAttributes(currentEagAttributes)
          .setUserAgent(userAgent)
          .setHttpConnectProxiedSocketAddress(proxiedAddr);
    TransportLogger transportLogger = new TransportLogger();
    // In case the transport logs in the constructor, use the subchannel logId
    transportLogger.logId = getLogId();
    ConnectionClientTransport transport =
        new CallTracingTransport(
            transportFactory
                .newClientTransport(address, options, transportLogger), callsTracer);
    transportLogger.logId = transport.getLogId();
    channelz.addClientSocket(transport);
    pendingTransport = transport;
    transports.add(transport);
    Runnable runnable = transport.start(new TransportListener(transport, address));
    if (runnable != null) {
      syncContext.executeLater(runnable);
    }
    channelLogger.log(ChannelLogLevel.INFO, "Started transport {0}", transportLogger.logId);
  }

  /**
   * Only called after all addresses attempted and failed (TRANSIENT_FAILURE).
   * @param status the causal status when the channel begins transition to
   *     TRANSIENT_FAILURE.
   */
  private void scheduleBackoff(final Status status) {
    syncContext.throwIfNotInThisSynchronizationContext();

    class EndOfCurrentBackoff implements Runnable {
      @Override
      public void run() {
        reconnectTask = null;
        channelLogger.log(ChannelLogLevel.INFO, "CONNECTING after backoff");
        gotoNonErrorState(CONNECTING);
        startNewTransport();
      }
    }

    gotoState(ConnectivityStateInfo.forTransientFailure(status));
    if (reconnectPolicy == null) {
      reconnectPolicy = backoffPolicyProvider.get();
    }
    long delayNanos =
        reconnectPolicy.nextBackoffNanos() - connectingTimer.elapsed(TimeUnit.NANOSECONDS);
    channelLogger.log(
        ChannelLogLevel.INFO,
        "TRANSIENT_FAILURE ({0}). Will reconnect after {1} ns",
        printShortStatus(status), delayNanos);
    Preconditions.checkState(reconnectTask == null, "previous reconnectTask is not done");
    reconnectTask = syncContext.schedule(
        new EndOfCurrentBackoff(),
        delayNanos,
        TimeUnit.NANOSECONDS,
        scheduledExecutor);
  }

  /**
   * Immediately attempt to reconnect if the current state is TRANSIENT_FAILURE. Otherwise this
   * method has no effect.
   */
  void resetConnectBackoff() {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        if (state.getState() != TRANSIENT_FAILURE) {
          return;
        }
        cancelReconnectTask();
        channelLogger.log(ChannelLogLevel.INFO, "CONNECTING; backoff interrupted");
        gotoNonErrorState(CONNECTING);
        startNewTransport();
      }
    });
  }

  private void gotoNonErrorState(final ConnectivityState newState) {
    syncContext.throwIfNotInThisSynchronizationContext();

    gotoState(ConnectivityStateInfo.forNonError(newState));
  }

  private void gotoState(final ConnectivityStateInfo newState) {
    syncContext.throwIfNotInThisSynchronizationContext();

    if (state.getState() != newState.getState()) {
      Preconditions.checkState(state.getState() != SHUTDOWN,
          "Cannot transition out of SHUTDOWN to " + newState);
      state = newState;
      callback.onStateChange(InternalSubchannel.this, newState);
    }
  }

  /** Replaces the existing addresses, avoiding unnecessary reconnects. */
  public void updateAddresses(final List<EquivalentAddressGroup> newAddressGroups) {
    Preconditions.checkNotNull(newAddressGroups, "newAddressGroups");
    checkListHasNoNulls(newAddressGroups, "newAddressGroups contains null entry");
    Preconditions.checkArgument(!newAddressGroups.isEmpty(), "newAddressGroups is empty");

    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        List<EquivalentAddressGroup> newImmutableAddressGroups =
            Collections.unmodifiableList(new ArrayList<>(newAddressGroups));
        ManagedClientTransport savedTransport = null;
        SocketAddress previousAddress = addressIndex.getCurrentAddress();
        addressIndex.updateGroups(newImmutableAddressGroups);
        addressGroups = newImmutableAddressGroups;
        if (state.getState() == READY || state.getState() == CONNECTING) {
          if (!addressIndex.seekTo(previousAddress)) {
            // Forced to drop the connection
            if (state.getState() == READY) {
              savedTransport = activeTransport;
              activeTransport = null;
              addressIndex.reset();
              gotoNonErrorState(IDLE);
            } else {
              savedTransport = pendingTransport;
              pendingTransport = null;
              addressIndex.reset();
              startNewTransport();
            }
          }
        }
        if (savedTransport != null) {
          savedTransport.shutdown(
              Status.UNAVAILABLE.withDescription(
                  "InternalSubchannel closed transport due to address change"));
        }
      }
    });
  }

  public void shutdown(final Status reason) {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        ManagedClientTransport savedActiveTransport;
        ConnectionClientTransport savedPendingTransport;
        if (state.getState() == SHUTDOWN) {
          return;
        }
        shutdownReason = reason;
        savedActiveTransport = activeTransport;
        savedPendingTransport = pendingTransport;
        activeTransport = null;
        pendingTransport = null;
        gotoNonErrorState(SHUTDOWN);
        addressIndex.reset();
        if (transports.isEmpty()) {
          handleTermination();
        }  // else: the callback will be run once all transports have been terminated
        cancelReconnectTask();
        if (savedActiveTransport != null) {
          savedActiveTransport.shutdown(reason);
        }
        if (savedPendingTransport != null) {
          savedPendingTransport.shutdown(reason);
        }
      }
    });
  }

  @Override
  public String toString() {
    // addressGroupsCopy being a little stale is fine, just avoid calling toString with the lock
    // since there may be many addresses.
    return MoreObjects.toStringHelper(this)
        .add("logId", logId.getId())
        .add("addressGroups", addressGroups)
        .toString();
  }

  private void handleTermination() {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        channelLogger.log(ChannelLogLevel.INFO, "Terminated");
        callback.onTerminated(InternalSubchannel.this);
      }
    });
  }

  private void handleTransportInUseState(
      final ConnectionClientTransport transport, final boolean inUse) {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        inUseStateAggregator.updateObjectInUse(transport, inUse);
      }
    });
  }

  void shutdownNow(final Status reason) {
    shutdown(reason);
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        Collection<ManagedClientTransport> transportsCopy =
            new ArrayList<ManagedClientTransport>(transports);

        for (ManagedClientTransport transport : transportsCopy) {
          transport.shutdownNow(reason);
        }
      }
    });
  }

  List<EquivalentAddressGroup> getAddressGroups() {
    return addressGroups;
  }

  private void cancelReconnectTask() {
    syncContext.throwIfNotInThisSynchronizationContext();

    if (reconnectTask != null) {
      reconnectTask.cancel();
      reconnectTask = null;
      reconnectPolicy = null;
    }
  }

  @Override
  public InternalLogId getLogId() {
    return logId;
  }

  @Override
  public ListenableFuture<ChannelStats> getStats() {
    final SettableFuture<ChannelStats> channelStatsFuture = SettableFuture.create();
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        ChannelStats.Builder builder = new ChannelStats.Builder();
        List<EquivalentAddressGroup> addressGroupsSnapshot = addressIndex.getGroups();
        List<InternalWithLogId> transportsSnapshot = new ArrayList<InternalWithLogId>(transports);
        builder.setTarget(addressGroupsSnapshot.toString()).setState(getState());
        builder.setSockets(transportsSnapshot);
        callsTracer.updateBuilder(builder);
        channelTracer.updateBuilder(builder);
        channelStatsFuture.set(builder.build());
      }
    });
    return channelStatsFuture;
  }

  ConnectivityState getState() {
    return state.getState();
  }

  private static void checkListHasNoNulls(List<?> list, String msg) {
    for (Object item : list) {
      Preconditions.checkNotNull(item, msg);
    }
  }

  /** Listener for real transports. */
  private class TransportListener implements ManagedClientTransport.Listener {
    final ConnectionClientTransport transport;
    final SocketAddress address;
    boolean shutdownInitiated = false;

    TransportListener(ConnectionClientTransport transport, SocketAddress address) {
      this.transport = transport;
      this.address = address;
    }

    @Override
    public void transportReady() {
      channelLogger.log(ChannelLogLevel.INFO, "READY");
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          reconnectPolicy = null;
          if (shutdownReason != null) {
            // activeTransport should have already been set to null by shutdown(). We keep it null.
            Preconditions.checkState(activeTransport == null,
                "Unexpected non-null activeTransport");
            transport.shutdown(shutdownReason);
          } else if (pendingTransport == transport) {
            activeTransport = transport;
            pendingTransport = null;
            gotoNonErrorState(READY);
          }
        }
      });
    }

    @Override
    public void transportInUse(boolean inUse) {
      handleTransportInUseState(transport, inUse);
    }

    @Override
    public void transportShutdown(final Status s) {
      channelLogger.log(
          ChannelLogLevel.INFO, "{0} SHUTDOWN with {1}", transport.getLogId(), printShortStatus(s));
      shutdownInitiated = true;
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (state.getState() == SHUTDOWN) {
            return;
          }
          if (activeTransport == transport) {
            activeTransport = null;
            addressIndex.reset();
            gotoNonErrorState(IDLE);
          } else if (pendingTransport == transport) {
            Preconditions.checkState(state.getState() == CONNECTING,
                "Expected state is CONNECTING, actual state is %s", state.getState());
            addressIndex.increment();
            // Continue reconnect if there are still addresses to try.
            if (!addressIndex.isValid()) {
              pendingTransport = null;
              addressIndex.reset();
              // Initiate backoff
              // Transition to TRANSIENT_FAILURE
              scheduleBackoff(s);
            } else {
              startNewTransport();
            }
          }
        }
      });
    }

    @Override
    public void transportTerminated() {
      Preconditions.checkState(
          shutdownInitiated, "transportShutdown() must be called before transportTerminated().");

      channelLogger.log(ChannelLogLevel.INFO, "{0} Terminated", transport.getLogId());
      channelz.removeClientSocket(transport);
      handleTransportInUseState(transport, false);
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          transports.remove(transport);
          if (state.getState() == SHUTDOWN && transports.isEmpty()) {
            handleTermination();
          }
        }
      });
    }
  }

  // All methods are called in syncContext
  abstract static class Callback {
    /**
     * Called when the subchannel is terminated, which means it's shut down and all transports
     * have been terminated.
     */
    @ForOverride
    void onTerminated(InternalSubchannel is) { }

    /**
     * Called when the subchannel's connectivity state has changed.
     */
    @ForOverride
    void onStateChange(InternalSubchannel is, ConnectivityStateInfo newState) { }

    /**
     * Called when the subchannel's in-use state has changed to true, which means at least one
     * transport is in use.
     */
    @ForOverride
    void onInUse(InternalSubchannel is) { }

    /**
     * Called when the subchannel's in-use state has changed to false, which means no transport is
     * in use.
     */
    @ForOverride
    void onNotInUse(InternalSubchannel is) { }
  }

  @VisibleForTesting
  static final class CallTracingTransport extends ForwardingConnectionClientTransport {
    private final ConnectionClientTransport delegate;
    private final CallTracer callTracer;

    private CallTracingTransport(ConnectionClientTransport delegate, CallTracer callTracer) {
      this.delegate = delegate;
      this.callTracer = callTracer;
    }

    @Override
    protected ConnectionClientTransport delegate() {
      return delegate;
    }

    @Override
    public ClientStream newStream(
        MethodDescriptor<?, ?> method, Metadata headers, CallOptions callOptions) {
      final ClientStream streamDelegate = super.newStream(method, headers, callOptions);
      return new ForwardingClientStream() {
        @Override
        protected ClientStream delegate() {
          return streamDelegate;
        }

        @Override
        public void start(final ClientStreamListener listener) {
          callTracer.reportCallStarted();
          super.start(new ForwardingClientStreamListener() {
            @Override
            protected ClientStreamListener delegate() {
              return listener;
            }

            @Override
            public void closed(Status status, Metadata trailers) {
              callTracer.reportCallEnded(status.isOk());
              super.closed(status, trailers);
            }

            @Override
            public void closed(
                Status status, RpcProgress rpcProgress, Metadata trailers) {
              callTracer.reportCallEnded(status.isOk());
              super.closed(status, rpcProgress, trailers);
            }
          });
        }
      };
    }
  }

  /** Index as in 'i', the pointer to an entry. Not a "search index." */
  @VisibleForTesting
  static final class Index {
    private List<EquivalentAddressGroup> addressGroups;
    private int groupIndex;
    private int addressIndex;

    public Index(List<EquivalentAddressGroup> groups) {
      this.addressGroups = groups;
    }

    public boolean isValid() {
      // addressIndex will never be invalid
      return groupIndex < addressGroups.size();
    }

    public boolean isAtBeginning() {
      return groupIndex == 0 && addressIndex == 0;
    }

    public void increment() {
      EquivalentAddressGroup group = addressGroups.get(groupIndex);
      addressIndex++;
      if (addressIndex >= group.getAddresses().size()) {
        groupIndex++;
        addressIndex = 0;
      }
    }

    public void reset() {
      groupIndex = 0;
      addressIndex = 0;
    }

    public SocketAddress getCurrentAddress() {
      return addressGroups.get(groupIndex).getAddresses().get(addressIndex);
    }

    public Attributes getCurrentEagAttributes() {
      return addressGroups.get(groupIndex).getAttributes();
    }

    public List<EquivalentAddressGroup> getGroups() {
      return addressGroups;
    }

    /** Update to new groups, resetting the current index. */
    public void updateGroups(List<EquivalentAddressGroup> newGroups) {
      addressGroups = newGroups;
      reset();
    }

    /** Returns false if the needle was not found and the current index was left unchanged. */
    public boolean seekTo(SocketAddress needle) {
      for (int i = 0; i < addressGroups.size(); i++) {
        EquivalentAddressGroup group = addressGroups.get(i);
        int j = group.getAddresses().indexOf(needle);
        if (j == -1) {
          continue;
        }
        this.groupIndex = i;
        this.addressIndex = j;
        return true;
      }
      return false;
    }
  }

  private String printShortStatus(Status status) {
    StringBuilder buffer = new StringBuilder();
    buffer.append(status.getCode());
    if (status.getDescription() != null) {
      buffer.append("(").append(status.getDescription()).append(")");
    }
    return buffer.toString();
  }

  @VisibleForTesting
  static final class TransportLogger extends ChannelLogger {
    // Changed just after construction to break a cyclic dependency.
    InternalLogId logId;

    @Override
    public void log(ChannelLogLevel level, String message) {
      ChannelLoggerImpl.logOnly(logId, level, message);
    }

    @Override
    public void log(ChannelLogLevel level, String messageFormat, Object... args) {
      ChannelLoggerImpl.logOnly(logId, level, messageFormat, args);
    }
  }
}
