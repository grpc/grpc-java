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
import io.grpc.*;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.InternalChannelz.ChannelStats;
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
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10383")
@ThreadSafe
final class InternalSubchannelExperimental implements InternalInstrumented<ChannelStats>, TransportProvider {

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
    @Nullable
    private ScheduledHandle shutdownDueToUpdateTask;
    @Nullable
    private ManagedClientTransport shutdownDueToUpdateTransport;

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
                    callback.onInUse(InternalSubchannelExperimental.this);
                }

                @Override
                protected void handleNotInUse() {
                    callback.onNotInUse(InternalSubchannelExperimental.this);
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

    InternalSubchannelExperimental(List<EquivalentAddressGroup> addressGroups, String authority, String userAgent,
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
              // TODO: verify that we only have this in IDLE and TF
              if (state.getState() == IDLE || state.getState() == TRANSIENT_FAILURE) {
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

        // move connectingTimer to PFLB?
//    if (addressIndex.isAtBeginning()) {
//      connectingTimer.reset().start();
//    }
//    SocketAddress address = addressIndex.getCurrentAddress();

        SocketAddress address = addressGroups.get(0).getAddresses().get(0);
        HttpConnectProxiedSocketAddress proxiedAddr = null;
        if (address instanceof HttpConnectProxiedSocketAddress) {
            proxiedAddr = (HttpConnectProxiedSocketAddress) address;
            address = proxiedAddr.getTargetAddress();
        }

//    Attributes currentEagAttributes = addressIndex.getCurrentEagAttributes();
        Attributes currentEagAttributes = addressGroups.get(0).getAttributes();
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
        Runnable runnable = transport.start(new TransportListener(transport));
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
            callback.onStateChange(InternalSubchannelExperimental.this, newState);
        }
    }

    /** Replaces the existing addresses, avoiding unnecessary reconnects. */
    @Deprecated
    public void updateAddresses(final List<EquivalentAddressGroup> newAddressGroups) {
//    Preconditions.checkNotNull(newAddressGroups, "newAddressGroups");
//    checkListHasNoNulls(newAddressGroups, "newAddressGroups contains null entry");
//    Preconditions.checkArgument(!newAddressGroups.isEmpty(), "newAddressGroups is empty");
//    final List<EquivalentAddressGroup> newImmutableAddressGroups =
//        Collections.unmodifiableList(new ArrayList<>(newAddressGroups));
//
//    syncContext.execute(new Runnable() {
//      @Override
//      public void run() {
//        ManagedClientTransport savedTransport = null;
//        SocketAddress previousAddress = addressIndex.getCurrentAddress();
//        addressIndex.updateGroups(newImmutableAddressGroups);
//        addressGroups = newImmutableAddressGroups;
//        if (state.getState() == READY || state.getState() == CONNECTING) {
//          if (!addressIndex.seekTo(previousAddress)) {
//            // Forced to drop the connection
//            if (state.getState() == READY) {
//              savedTransport = activeTransport;
//              activeTransport = null;
//              addressIndex.reset();
//              gotoNonErrorState(IDLE);
//            } else {
//              pendingTransport.shutdown(
//                  Status.UNAVAILABLE.withDescription(
//                    "InternalSubchannelExperimental closed pending transport due to address change"));
//              pendingTransport = null;
//              addressIndex.reset();
//              startNewTransport();
//            }
//          }
//        }
//        if (savedTransport != null) {
//          if (shutdownDueToUpdateTask != null) {
//            // Keeping track of multiple shutdown tasks adds complexity, and shouldn't generally be
//            // necessary. This transport has probably already had plenty of time.
//            shutdownDueToUpdateTransport.shutdown(
//                Status.UNAVAILABLE.withDescription(
//                    "InternalSubchannelExperimental closed transport early due to address change"));
//            shutdownDueToUpdateTask.cancel();
//            shutdownDueToUpdateTask = null;
//            shutdownDueToUpdateTransport = null;
//          }
//          // Avoid needless RPC failures by delaying the shutdown. See
//          // https://github.com/grpc/grpc-java/issues/2562
//          shutdownDueToUpdateTransport = savedTransport;
//          shutdownDueToUpdateTask = syncContext.schedule(
//              new Runnable() {
//                @Override public void run() {
//                  ManagedClientTransport transport = shutdownDueToUpdateTransport;
//                  shutdownDueToUpdateTask = null;
//                  shutdownDueToUpdateTransport = null;
//                  transport.shutdown(
//                      Status.UNAVAILABLE.withDescription(
//                          "InternalSubchannelExperimental closed transport due to address change"));
//                }
//              },
//              ManagedChannelImpl.SUBCHANNEL_SHUTDOWN_DELAY_SECONDS,
//              TimeUnit.SECONDS,
//              scheduledExecutor);
//        }
//      }
//    });
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
//        addressIndex.reset();
                if (transports.isEmpty()) {
                    handleTermination();
                }  // else: the callback will be run once all transports have been terminated
                cancelReconnectTask();
                if (shutdownDueToUpdateTask != null) {
                    shutdownDueToUpdateTask.cancel();
                    shutdownDueToUpdateTransport.shutdown(reason);
                    shutdownDueToUpdateTask = null;
                    shutdownDueToUpdateTransport = null;
                }
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
                callback.onTerminated(InternalSubchannelExperimental.this);
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
                List<EquivalentAddressGroup> addressGroupsSnapshot = addressGroups;
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
        boolean shutdownInitiated = false;

        TransportListener(ConnectionClientTransport transport) {
            this.transport = transport;
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
                // told to shutdown when READY
                activeTransport = null;
                gotoNonErrorState(IDLE);
              } else if (pendingTransport == transport) {
                // told to shutdown when CONNECTING
                Preconditions.checkState(state.getState() == CONNECTING,
                    "Expected state is CONNECTING, actual state is %s", state.getState());
                scheduleBackoff(s);
              } else { // TODO: may not be needed--verify cases
                scheduleBackoff(s);
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
        void onTerminated(InternalSubchannelExperimental is) { }

        /**
         * Called when the subchannel's connectivity state has changed.
         */
        @ForOverride
        void onStateChange(InternalSubchannelExperimental is, ConnectivityStateInfo newState) { }

        /**
         * Called when the subchannel's in-use state has changed to true, which means at least one
         * transport is in use.
         */
        @ForOverride
        void onInUse(InternalSubchannelExperimental is) { }

        /**
         * Called when the subchannel's in-use state has changed to false, which means no transport is
         * in use.
         */
        @ForOverride
        void onNotInUse(InternalSubchannelExperimental is) { }
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
                MethodDescriptor<?, ?> method, Metadata headers, CallOptions callOptions,
                ClientStreamTracer[] tracers) {
            final ClientStream streamDelegate = super.newStream(method, headers, callOptions, tracers);
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

    private String printShortStatus(Status status) {
        StringBuilder buffer = new StringBuilder();
        buffer.append(status.getCode());
        if (status.getDescription() != null) {
            buffer.append("(").append(status.getDescription()).append(")");
        }
        if (status.getCause() != null) {
            buffer.append("[").append(status.getCause()).append("]");
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