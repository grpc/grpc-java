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

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.Cluster.DiscoveryType;
import io.envoyproxy.envoy.api.v2.Cluster.LbPolicy;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.XdsLbState.LbEndpoint;
import io.grpc.xds.XdsLbState.Locality;
import io.grpc.xds.XdsLbState.LocalityInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADS client implementation.
 */
final class XdsComms {
  private final ManagedChannel channel;
  private final Helper helper;
  private final LoadBalancerRegistry lbRegistry;

  // never null
  private AdsStream adsStream;

  private final class AdsStream {
    static final String CDS_TYPE_URL =
        "type.googleapis.com/envoy.api.v2.Cluster";
    static final String EDS_TYPE_URL =
        "type.googleapis.com/envoy.api.v2.ClusterLoadAssignment";
    static final String TRAFFICDIRECTOR_GRPC_HOSTNAME = "TRAFFICDIRECTOR_GRPC_HOSTNAME";
    static final String ROUND_ROBIN = "round_robin";
    final LocalityStore subchannelStore;

    final AdsStreamCallback adsStreamCallback;

    final StreamObserver<DiscoveryRequest> xdsRequestWriter;

    LoadBalancerProvider lbProvider;

    final StreamObserver<DiscoveryResponse> xdsResponseReader =
        new StreamObserver<DiscoveryResponse>() {

          boolean firstResponseReceived;
          boolean cdsResponseReceived;

          @Override
          public void onNext(final DiscoveryResponse value) {

            class HandleResponseRunnable implements Runnable {

              @Override
              public void run() {
                if (!firstResponseReceived) {
                  firstResponseReceived = true;
                  adsStreamCallback.onWorking();
                }
                String typeUrl = value.getTypeUrl();
                if (CDS_TYPE_URL.equals(typeUrl)) {
                  // Assuming standard mode for now
                  // Assuming there is only one CDS response per stream for now.
                  // TODO: handle the case that this CDS response is not the first one.

                  cdsResponseReceived = true;
                  Cluster cluster;
                  try {
                    // maybe better to run this deserialization task out of syncContext?
                    cluster = value.getResources(0).unpack(Cluster.class);
                  } catch (InvalidProtocolBufferException | NullPointerException e) {
                    cancelRpc("Received invalid CDS response", e);
                    return;
                  }

                  DiscoveryType discoveryType = cluster.getType();
                  if (!DiscoveryType.EDS.equals(discoveryType)) {
                    cancelRpc(
                        "Received invalid CDS response. Wrong DiscoveryType '" + discoveryType
                            + "'",
                        null);
                    return;
                  }

                  LbPolicy lbPolicy = cluster.getLbPolicy();
                  if (lbPolicy == null) {
                    cancelRpc("Received invalid CDS response. lbPolicy is null", null);
                    return;
                  }

                  lbProvider = lbRegistry.getProvider(lbPolicy.name().toLowerCase());

                  if (lbProvider == null)  {
                    helper.getChannelLogger().log(
                        ChannelLogLevel.INFO,
                        "Unable to load lbPolicy '{0}', use '{1}' instead", lbPolicy, ROUND_ROBIN);
                    lbProvider =  checkNotNull(
                        lbRegistry.getProvider(ROUND_ROBIN),
                        "Unable to find '%s' LoadBalancer", ROUND_ROBIN);

                  }
                  subchannelStore.updateLoadBalancerProvider(lbProvider);

                  // send EDS request
                  xdsRequestWriter.onNext(
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
                          .addResourceNames(helper.getAuthority())
                          .setTypeUrl(EDS_TYPE_URL).build());
                } else if (EDS_TYPE_URL.equals(typeUrl)) {
                  // Assuming standard mode.
                  if (!cdsResponseReceived) {
                    cancelRpc("Received EDS response prior to CDS response", null);
                    return;
                  }

                  ClusterLoadAssignment clusterLoadAssignment;
                  try {
                    // maybe better to run this deserialization task out of syncContext?
                    clusterLoadAssignment =
                        value.getResources(0).unpack(ClusterLoadAssignment.class);
                  } catch (InvalidProtocolBufferException | NullPointerException e) {
                    cancelRpc("Received invalid EDS response", e);
                    return;
                  }

                  List<LocalityLbEndpoints> localities = clusterLoadAssignment.getEndpointsList();
                  Map<Locality, LocalityInfo> localityEndpointsMapping = new LinkedHashMap<>();
                  for (LocalityLbEndpoints localityLbEndpoints : localities) {
                    io.envoyproxy.envoy.api.v2.core.Locality localityProto =
                        localityLbEndpoints.getLocality();
                    Locality locality = new Locality(localityProto);
                    List<LbEndpoint> lbEndPoints = new ArrayList<>();
                    for (io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint lbEndpoint
                        : localityLbEndpoints.getLbEndpointsList()) {
                      lbEndPoints.add(new LbEndpoint(lbEndpoint));
                    }
                    int localityWeight = localityLbEndpoints.getLoadBalancingWeight().getValue();

                    localityEndpointsMapping.put(
                        locality, new LocalityInfo(lbEndPoints, localityWeight));
                  }

                  localityEndpointsMapping = Collections.unmodifiableMap(localityEndpointsMapping);

                  // TODO: parse drop_percentage, and also updateLoacalistyStore with dropPercentage
                  subchannelStore.updateLocalityStore(localityEndpointsMapping);
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
                  }
                });
            // TODO: more impl
          }

          @Override
          public void onCompleted() {
            // TODO: impl
          }
        };

    boolean cancelled;
    boolean closed;

    AdsStream(AdsStreamCallback adsStreamCallback, LocalityStore subchannelStore) {
      this.adsStreamCallback = adsStreamCallback;
      this.xdsRequestWriter = AggregatedDiscoveryServiceGrpc.newStub(channel).withWaitForReady()
          .streamAggregatedResources(xdsResponseReader);
      this.subchannelStore = subchannelStore;

      // Assuming standard mode, send CDS request
      xdsRequestWriter.onNext(
          DiscoveryRequest.newBuilder()
              .setNode(Node.newBuilder()
                  .setMetadata(Struct.newBuilder()
                      .putFields(
                          TRAFFICDIRECTOR_GRPC_HOSTNAME,
                          Value.newBuilder().setStringValue(helper.getAuthority()).build())))
              .addResourceNames(helper.getAuthority())
              .setTypeUrl(CDS_TYPE_URL).build());
    }

    AdsStream(AdsStream adsStream) {
      this(adsStream.adsStreamCallback, adsStream.subchannelStore);
    }

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
  XdsComms(
      ManagedChannel channel, Helper helper, AdsStreamCallback adsStreamCallback,
      LocalityStore subchannelStore, LoadBalancerRegistry lbRegistry) {
    this.channel = checkNotNull(channel, "channel");
    this.helper = checkNotNull(helper, "helper");
    this.adsStream = new AdsStream(
        checkNotNull(adsStreamCallback, "adsStreamCallback"),
        checkNotNull(subchannelStore, "subchannelStore"));
    this.lbRegistry = lbRegistry;
  }

  void shutdownChannel() {
    channel.shutdown();
    shutdownLbRpc("Loadbalancer client shutdown");
  }

  void refreshAdsStream() {
    checkState(!channel.isShutdown(), "channel is alreday shutdown");

    if (adsStream.closed || adsStream.cancelled) {
      adsStream = new AdsStream(adsStream);
    }
  }

  void shutdownLbRpc(String message) {
    adsStream.cancelRpc(message, null);
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
  }
}
