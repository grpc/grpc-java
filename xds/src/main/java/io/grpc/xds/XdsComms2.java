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
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.LoadBalancer.Helper;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;

/**
 * ADS client implementation.
 */
// TODO(zdapeng): This is a temporary and easy refactor of XdsComms, will be replaced by XdsClient.
// Tests are deferred in XdsClientTest, otherwise it's just a refactor of XdsCommsTest.
final class XdsComms2 {
  private final ManagedChannel channel;
  private final Helper helper;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Supplier<Stopwatch> stopwatchSupplier;

  @CheckForNull
  private ScheduledHandle adsRpcRetryTimer;

  // never null
  private BackoffPolicy adsRpcRetryPolicy;
  // never null
  private AdsStream adsStream;

  private final class AdsStream {
    static final String EDS_TYPE_URL =
        "type.googleapis.com/envoy.api.v2.ClusterLoadAssignment";

    final AdsStreamCallback adsStreamCallback;
    final StreamObserver<DiscoveryRequest> xdsRequestWriter;
    final Stopwatch retryStopwatch = stopwatchSupplier.get().start();

    final StreamObserver<DiscoveryResponse> xdsResponseReader =
        new StreamObserver<DiscoveryResponse>() {
          // Must be accessed in SynchronizationContext
          boolean firstEdsResponseReceived;

          @Override
          public void onNext(final DiscoveryResponse value) {

            class HandleResponseRunnable implements Runnable {

              @Override
              public void run() {
                String typeUrl = value.getTypeUrl();
                if (EDS_TYPE_URL.equals(typeUrl)) {
                  // Assuming standard mode.

                  ClusterLoadAssignment clusterLoadAssignment;
                  try {
                    // maybe better to run this deserialization task out of syncContext?
                    clusterLoadAssignment =
                        value.getResources(0).unpack(ClusterLoadAssignment.class);
                  } catch (InvalidProtocolBufferException | RuntimeException e) {
                    cancelRpc("Received invalid EDS response", e);
                    adsStreamCallback.onError();
                    scheduleRetry();
                    return;
                  }

                  helper.getChannelLogger().log(
                      ChannelLogLevel.DEBUG,
                      "Received an EDS response: {0}", clusterLoadAssignment);
                  firstEdsResponseReceived = true;
                  adsStreamCallback.onEdsResponse(clusterLoadAssignment);
                }
              }
            }

            helper.getSynchronizationContext().execute(new HandleResponseRunnable());
          }

          @Override
          public void onError(Throwable t) {
            helper.getSynchronizationContext().execute(
                new Runnable() {
                  @Override
                  public void run() {
                    closed = true;
                    if (cancelled) {
                      return;
                    }
                    adsStreamCallback.onError();
                    scheduleRetry();
                  }
                });
          }

          @Override
          public void onCompleted() {
            onError(Status.INTERNAL.withDescription("Server closed the ADS streaming RPC")
                .asException());
          }

          // run in SynchronizationContext
          void scheduleRetry() {
            if (channel.isShutdown()) {
              return;
            }

            checkState(
                cancelled || closed,
                "Scheduling retry while the stream is neither cancelled nor closed");

            checkState(
                adsRpcRetryTimer == null, "Scheduling retry while a retry is already pending");

            class AdsRpcRetryTask implements Runnable {
              @Override
              public void run() {
                adsRpcRetryTimer = null;
                refreshAdsStream();
              }
            }

            if (firstEdsResponseReceived) {
              // Reset the backoff sequence if balancer has sent the initial response
              adsRpcRetryPolicy = backoffPolicyProvider.get();
              // Retry immediately
              helper.getSynchronizationContext().execute(new AdsRpcRetryTask());
              return;
            }

            adsRpcRetryTimer = helper.getSynchronizationContext().schedule(
                new AdsRpcRetryTask(),
                adsRpcRetryPolicy.nextBackoffNanos() - retryStopwatch.elapsed(TimeUnit.NANOSECONDS),
                TimeUnit.NANOSECONDS,
                helper.getScheduledExecutorService());
          }
        };

    boolean cancelled;
    boolean closed;

    AdsStream(AdsStreamCallback adsStreamCallback) {
      this.adsStreamCallback = adsStreamCallback;
      this.xdsRequestWriter = AggregatedDiscoveryServiceGrpc.newStub(channel).withWaitForReady()
          .streamAggregatedResources(xdsResponseReader);

      checkState(adsRpcRetryTimer == null, "Creating AdsStream while retry is pending");
      // Assuming standard mode, and send EDS request only
      DiscoveryRequest edsRequest =
          DiscoveryRequest.newBuilder()
              .setNode(Node.newBuilder()
                  .setMetadata(Struct.newBuilder()
                      .putFields(
                          "endpoints_required",
                          Value.newBuilder().setBoolValue(true).build())))
              .setTypeUrl(EDS_TYPE_URL)
              // In the future, the right resource name can be obtained from CDS response.
              .addResourceNames(helper.getAuthority()).build();
      helper.getChannelLogger().log(ChannelLogLevel.DEBUG, "Sending EDS request {0}", edsRequest);
      xdsRequestWriter.onNext(edsRequest);
    }

    AdsStream(AdsStream adsStream) {
      this(adsStream.adsStreamCallback);
    }

    // run in SynchronizationContext
    void cancelRpc(String message, Throwable cause) {
      if (cancelled) {
        return;
      }
      cancelled = true;
      xdsRequestWriter.onError(
          Status.CANCELLED.withDescription(message).withCause(cause).asRuntimeException());
    }
  }

  /**
   * Starts a new ADS streaming RPC.
   */
  XdsComms2(
      ManagedChannel channel, Helper helper, AdsStreamCallback adsStreamCallback,
      BackoffPolicy.Provider backoffPolicyProvider, Supplier<Stopwatch> stopwatchSupplier) {
    this.channel = checkNotNull(channel, "channel");
    this.helper = checkNotNull(helper, "helper");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    this.adsStream = new AdsStream(
        checkNotNull(adsStreamCallback, "adsStreamCallback"));
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.adsRpcRetryPolicy = backoffPolicyProvider.get();
  }

  // run in SynchronizationContext
  void refreshAdsStream() {
    checkState(!channel.isShutdown(), "channel is alreday shutdown");

    if (adsStream.closed || adsStream.cancelled) {
      cancelRetryTimer();
      adsStream = new AdsStream(adsStream);
    }
  }

  // run in SynchronizationContext
  // TODO: Change method name to shutdown or shutdownXdsComms if that gives better semantics (
  //  cancel LB RPC and clean up retry timer).
  void shutdownLbRpc() {
    adsStream.cancelRpc("shutdown", null);
    cancelRetryTimer();
  }

  // run in SynchronizationContext
  private void cancelRetryTimer() {
    if (adsRpcRetryTimer != null) {
      adsRpcRetryTimer.cancel();
      adsRpcRetryTimer = null;
    }
  }

  /**
   * Callback on ADS stream events. The callback methods should be called in a proper {@link
   * io.grpc.SynchronizationContext}.
   */
  interface AdsStreamCallback {
    void onEdsResponse(ClusterLoadAssignment clusterLoadAssignment);

    void onError();
  }
}
