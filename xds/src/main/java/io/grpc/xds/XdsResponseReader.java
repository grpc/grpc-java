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

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.XdsClientImpl.EndpointWatchers;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

final class XdsResponseReader implements StreamObserver<DiscoveryResponse> {
  private static final Logger logger = XdsClientImpl.logger;

  private final StreamActivityWatcher streamActivityWatcher;
  private final EndpointWatchers endpointWatchers;
  private final SynchronizationContext syncCtx;

  XdsResponseReader(
      StreamActivityWatcher streamActivityWatcher,
      EndpointWatchers endpointWatchers,
      SynchronizationContext syncCtx) {
    this.streamActivityWatcher = streamActivityWatcher;
    this.endpointWatchers = endpointWatchers;
    this.syncCtx = syncCtx;
  }

  @Override
  public void onNext(final DiscoveryResponse value) {

    class HandleResponseRunnable implements Runnable {
      @Override
      public void run() {
        streamActivityWatcher.responseReceived();

        String typeUrl = value.getTypeUrl();
        if (XdsClientImpl.EDS_TYPE_URL.equals(typeUrl)) {
          handleEdsResponse(value);
        }
      }
    }

    syncCtx.execute(new HandleResponseRunnable());
  }

  @Override
  public void onError(final Throwable t) {
    logger.log(Level.INFO, "XdsClient received an error", t);

    syncCtx.execute(
        new Runnable() {
          @Override
          public void run() {
            handleStreamClosed(Status.fromThrowable(t));
          }
        });
  }

  @Override
  public void onCompleted() {
    final String errMsg = "Traffic director closed the ADS stream";
    logger.log(Level.INFO, errMsg);
    syncCtx.execute(
        new Runnable() {
          @Override
          public void run() {
            handleStreamClosed(Status.UNAVAILABLE.withDescription(errMsg));
          }
        });
  }

  private void handleEdsResponse(DiscoveryResponse value) {
    List<ClusterLoadAssignment> clusterLoadAssignments = new ArrayList<>(value.getResourcesCount());
    try {
      // maybe better to run this deserialization task out of syncContext?
      for (Any any : value.getResourcesList()) {
        clusterLoadAssignments.add(any.unpack(ClusterLoadAssignment.class));
      }
    } catch (InvalidProtocolBufferException | RuntimeException e) {
      if (logger.isLoggable(Level.INFO)) {
        logger.log(
            Level.INFO,
            "Unable to extract clusterLoadAssignment from EDS response: " + value,
            e);
      }
      // TODO(zdapeng): NACK
      return;
    }

    logger.log(
        Level.FINE, "Received an EDS response: {0}", clusterLoadAssignments);

    // TODO(zdapeng): ACK (but if endpointWatchers is empty then send NACK?)
    for (ClusterLoadAssignment clusterLoadAssignment : clusterLoadAssignments) {
      endpointWatchers.onEndpointUpdate(clusterLoadAssignment);
    }
  }

  // Must run in SynchronizationContext
  private void handleStreamClosed(Status error) {
    checkArgument(!error.isOk(), "unexpected OK status");

    endpointWatchers.onError(error);
    streamActivityWatcher.streamClosed();
  }

  interface StreamActivityWatcher {
    void responseReceived();

    void streamClosed();
  }
}
