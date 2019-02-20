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

import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc;
import io.grpc.LoadBalancer.Helper;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import javax.annotation.CheckReturnValue;

/**
 * ADS client implementation.
 */
final class XdsComms {
  private final ManagedChannel channel;
  private final Helper helper;
  private final AdsStreamCallback adsStreamCallback;

  private final StreamObserver<DiscoveryRequest> xdsRequestWriter;

  private final StreamObserver<DiscoveryResponse> xdsResponseReader =
      new StreamObserver<DiscoveryResponse>() {

        boolean firstResponseReceived;

        @Override
        public void onNext(DiscoveryResponse value) {
          if (!firstResponseReceived) {
            firstResponseReceived = true;
            helper.getSynchronizationContext().execute(
                new Runnable() {
                  @Override
                  public void run() {
                    adsStreamCallback.onWorking();
                  }
                });
          }
          // TODO: more impl
        }

        @Override
        public void onError(Throwable t) {
          helper.getSynchronizationContext().execute(
              new Runnable() {
                @Override
                public void run() {
                  if (cancelled) {
                    return;
                  }
                  closed = true;
                  adsStreamCallback.onClosed();
                }
              });
          // TODO: more impl
        }

        @Override
        public void onCompleted() {
          // TODO: impl
        }
      };

  private boolean cancelled;
  private boolean closed;

  /**
   * Starts a new ADS streaming RPC.
   */
  XdsComms(
      ManagedChannel channel, Helper helper, AdsStreamCallback adsStreamCallback) {
    this.channel = checkNotNull(channel, "channel");
    this.helper = checkNotNull(helper, "helper");
    this.adsStreamCallback = checkNotNull(adsStreamCallback, "adsStreamCallback");
    xdsRequestWriter = AggregatedDiscoveryServiceGrpc.newStub(channel).withWaitForReady()
        .streamAggregatedResources(xdsResponseReader);
  }

  void shutdownChannel() {
    channel.shutdown();
    shutdownLbRpc("Loadbalancer client shutdown");
  }

  @CheckReturnValue
  XdsComms maybeRestart() {
    if (closed || cancelled) {
      return new XdsComms(channel, helper, adsStreamCallback);
    }
    return this;
  }

  void shutdownLbRpc(String message) {
    if (cancelled) {
      return;
    }
    cancelled = true;
    xdsRequestWriter.onError(Status.CANCELLED.withDescription(message).asRuntimeException());
  }

  interface AdsStreamCallback {

    /**
     * Once the response observer receives the first response.
     */
    void onWorking();

    /**
     * Once the ADS stream is closed.
     */
    void onClosed();
  }
}
