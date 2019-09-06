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
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.type.FractionalPercent;
import io.envoyproxy.envoy.type.FractionalPercent.DenominatorType;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.Helper;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;

/**
 * ADS client implementation.
 */
final class XdsComms {
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

  /**
   * Information about the locality from EDS response.
   */
  static final class LocalityInfo {
    final List<EquivalentAddressGroup> eags;
    final List<Integer> endPointWeights;
    final int localityWeight;

    LocalityInfo(Collection<LbEndpoint> lbEndPoints, int localityWeight) {
      List<EquivalentAddressGroup> eags = new ArrayList<>(lbEndPoints.size());
      List<Integer> endPointWeights = new ArrayList<>(lbEndPoints.size());
      for (LbEndpoint lbEndPoint : lbEndPoints) {
        eags.add(lbEndPoint.eag);
        endPointWeights.add(lbEndPoint.endPointWeight);
      }
      this.eags = Collections.unmodifiableList(eags);
      this.endPointWeights = Collections.unmodifiableList(new ArrayList<>(endPointWeights));
      this.localityWeight = localityWeight;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LocalityInfo that = (LocalityInfo) o;
      return localityWeight == that.localityWeight
          && Objects.equal(eags, that.eags)
          && Objects.equal(endPointWeights, that.endPointWeights);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(eags, endPointWeights, localityWeight);
    }
  }

  static final class LbEndpoint {
    final EquivalentAddressGroup eag;
    final int endPointWeight;

    LbEndpoint(io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint lbEndpointProto) {

      this(
          new EquivalentAddressGroup(ImmutableList.of(fromEnvoyProtoAddress(lbEndpointProto))),
          lbEndpointProto.getLoadBalancingWeight().getValue());
    }

    @VisibleForTesting
    LbEndpoint(EquivalentAddressGroup eag, int endPointWeight) {
      this.eag = eag;
      this.endPointWeight = endPointWeight;
    }

    private static java.net.SocketAddress fromEnvoyProtoAddress(
        io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint lbEndpointProto) {
      SocketAddress socketAddress = lbEndpointProto.getEndpoint().getAddress().getSocketAddress();
      return new InetSocketAddress(socketAddress.getAddress(), socketAddress.getPortValue());
    }
  }

  static final class DropOverload {
    final String category;
    final int dropsPerMillion;

    DropOverload(String category, int dropsPerMillion) {
      this.category = category;
      this.dropsPerMillion = dropsPerMillion;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      DropOverload that = (DropOverload) o;
      return dropsPerMillion == that.dropsPerMillion && Objects.equal(category, that.category);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(category, dropsPerMillion);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("category", category)
          .add("dropsPerMillion", dropsPerMillion)
          .toString();
    }
  }

  private final class AdsStream {
    static final String EDS_TYPE_URL =
        "type.googleapis.com/envoy.api.v2.ClusterLoadAssignment";
    static final String TRAFFICDIRECTOR_GRPC_HOSTNAME = "TRAFFICDIRECTOR_GRPC_HOSTNAME";
    final LocalityStore localityStore;

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
                  if (!firstEdsResponseReceived) {
                    firstEdsResponseReceived = true;
                    adsStreamCallback.onWorking();
                  }

                  List<ClusterLoadAssignment.Policy.DropOverload> dropOverloadsProto =
                      clusterLoadAssignment.getPolicy().getDropOverloadsList();
                  ImmutableList.Builder<DropOverload> dropOverloadsBuilder
                      = ImmutableList.builder();
                  for (ClusterLoadAssignment.Policy.DropOverload dropOverload
                      : dropOverloadsProto) {
                    int rateInMillion = rateInMillion(dropOverload.getDropPercentage());
                    dropOverloadsBuilder.add(new DropOverload(
                        dropOverload.getCategory(), rateInMillion));
                    if (rateInMillion == 1000_000) {
                      adsStreamCallback.onAllDrop();
                      break;
                    }
                  }
                  ImmutableList<DropOverload> dropOverloads = dropOverloadsBuilder.build();
                  localityStore.updateDropPercentage(dropOverloads);

                  List<LocalityLbEndpoints> localities = clusterLoadAssignment.getEndpointsList();
                  Map<XdsLocality, LocalityInfo> localityEndpointsMapping = new LinkedHashMap<>();
                  for (LocalityLbEndpoints localityLbEndpoints : localities) {
                    io.envoyproxy.envoy.api.v2.core.Locality localityProto =
                        localityLbEndpoints.getLocality();
                    XdsLocality locality = XdsLocality.fromLocalityProto(localityProto);
                    List<LbEndpoint> lbEndPoints = new ArrayList<>();
                    for (io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint lbEndpoint
                        : localityLbEndpoints.getLbEndpointsList()) {
                      lbEndPoints.add(new LbEndpoint(lbEndpoint));
                    }
                    int localityWeight = localityLbEndpoints.getLoadBalancingWeight().getValue();

                    if (localityWeight != 0) {
                      localityEndpointsMapping.put(
                          locality, new LocalityInfo(lbEndPoints, localityWeight));
                    }
                  }

                  localityEndpointsMapping = Collections.unmodifiableMap(localityEndpointsMapping);

                  localityStore.updateLocalityStore(localityEndpointsMapping);
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

    AdsStream(AdsStreamCallback adsStreamCallback, LocalityStore localityStore) {
      this.adsStreamCallback = adsStreamCallback;
      this.xdsRequestWriter = AggregatedDiscoveryServiceGrpc.newStub(channel).withWaitForReady()
          .streamAggregatedResources(xdsResponseReader);
      this.localityStore = localityStore;

      checkState(adsRpcRetryTimer == null, "Creating AdsStream while retry is pending");
      // Assuming standard mode, and send EDS request only
      DiscoveryRequest edsRequest =
          DiscoveryRequest.newBuilder()
              .setNode(Node.newBuilder()
                  .setMetadata(Struct.newBuilder()
                      .putFields(
                          TRAFFICDIRECTOR_GRPC_HOSTNAME,
                          Value.newBuilder().setStringValue(helper.getAuthority())
                              .build())
                      .putFields(
                          "endpoints_required",
                          Value.newBuilder().setBoolValue(true).build())))
              .setTypeUrl(EDS_TYPE_URL).build();
      helper.getChannelLogger().log(ChannelLogLevel.DEBUG, "Sending EDS request {0}", edsRequest);
      xdsRequestWriter.onNext(edsRequest);
    }

    AdsStream(AdsStream adsStream) {
      this(adsStream.adsStreamCallback, adsStream.localityStore);
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

  private static int rateInMillion(FractionalPercent fractionalPercent) {
    int numerator = fractionalPercent.getNumerator();
    checkArgument(numerator >= 0, "numerator shouldn't be negative in %s", fractionalPercent);

    DenominatorType type = fractionalPercent.getDenominator();
    switch (type) {
      case TEN_THOUSAND:
        numerator *= 100;
        break;
      case HUNDRED:
        numerator *= 100_00;
        break;
      case MILLION:
        break;
      default:
        throw new IllegalArgumentException("unknown denominator type of " + fractionalPercent);
    }

    if (numerator > 1000_000) {
      numerator = 1000_000;
    }

    return numerator;
  }

  /**
   * Starts a new ADS streaming RPC.
   */
  XdsComms(
      ManagedChannel channel, Helper helper, AdsStreamCallback adsStreamCallback,
      LocalityStore localityStore, BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    this.channel = checkNotNull(channel, "channel");
    this.helper = checkNotNull(helper, "helper");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    this.adsStream = new AdsStream(
        checkNotNull(adsStreamCallback, "adsStreamCallback"),
        checkNotNull(localityStore, "localityStore"));
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
  void shutdownLbRpc(String message) {
    adsStream.cancelRpc(message, null);
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

    /**
     * Once the response observer receives the first response.
     */
    void onWorking();

    /**
     * Once an error occurs in ADS stream.
     */
    void onError();

    /**
     * Once receives a response indicating that 100% of calls should be dropped.
     */
    void onAllDrop();
  }
}
