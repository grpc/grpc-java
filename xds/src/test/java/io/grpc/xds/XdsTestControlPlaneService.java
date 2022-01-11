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

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.SynchronizationContext;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

class XdsTestControlPlaneService extends
    AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase {
  private static final Logger logger = Logger.getLogger(XdsTestControlPlaneService.class.getName());

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          logger.log(Level.SEVERE, "Exception!" + e);
        }
      });

  static final String ADS_TYPE_URL_LDS =
      "type.googleapis.com/envoy.config.listener.v3.Listener";
  static final String ADS_TYPE_URL_RDS =
      "type.googleapis.com/envoy.config.route.v3.RouteConfiguration";
  static final String ADS_TYPE_URL_CDS =
      "type.googleapis.com/envoy.config.cluster.v3.Cluster";
  static final String ADS_TYPE_URL_EDS =
      "type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment";

  private final Map<String, HashMap<String, Object>> xdsResources = new HashMap<>();
  private ImmutableMap<String, HashMap<StreamObserver<DiscoveryResponse>, List<String>>> subscribers
      = ImmutableMap.of(
          ADS_TYPE_URL_LDS, new HashMap<StreamObserver<DiscoveryResponse>, List<String>>(),
          ADS_TYPE_URL_RDS, new HashMap<StreamObserver<DiscoveryResponse>, List<String>>(),
          ADS_TYPE_URL_CDS, new HashMap<StreamObserver<DiscoveryResponse>, List<String>>(),
          ADS_TYPE_URL_EDS, new HashMap<StreamObserver<DiscoveryResponse>, List<String>>()
          );
  private final ImmutableMap<String, AtomicInteger> xdsVersions = ImmutableMap.of(
      ADS_TYPE_URL_LDS, new AtomicInteger(1),
      ADS_TYPE_URL_RDS, new AtomicInteger(1),
      ADS_TYPE_URL_CDS, new AtomicInteger(1),
      ADS_TYPE_URL_EDS, new AtomicInteger(1)
  );
  private final ImmutableMap<String, AtomicInteger> xdsNonces = ImmutableMap.of(
      ADS_TYPE_URL_LDS, new AtomicInteger(0),
      ADS_TYPE_URL_RDS, new AtomicInteger(0),
      ADS_TYPE_URL_CDS, new AtomicInteger(0),
      ADS_TYPE_URL_EDS, new AtomicInteger(0)
  );


  // treat all the resource types as state-of-the-world, send back all resources of a particular
  // type when any of them change.
  public <T> void setXdsConfig(final String type, final Map<String, T> resources) {
    logger.log(Level.FINE, "setting config {0} {1}", new Object[]{type, resources});
    syncContext.execute(new Runnable() {
      @SuppressWarnings("unchecked")
      @Override
      public void run() {
        HashMap<String, Object> copyResources = (HashMap<String, Object>) new HashMap<>(resources);
        xdsResources.put(type, copyResources);
        for (Map.Entry<StreamObserver<DiscoveryResponse>, List<String>> entry :
            subscribers.get(type).entrySet()) {
          entry.getKey().onNext(generateResponse(type, entry.getValue()));
        }
      }
    });
  }

  @Override
  public StreamObserver<DiscoveryRequest> streamAggregatedResources(
      final StreamObserver<DiscoveryResponse> responseObserver) {
    final StreamObserver<DiscoveryRequest> requestObserver =
        new StreamObserver<DiscoveryRequest>() {
      @Override
      public void onNext(final DiscoveryRequest value) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            logger.log(Level.FINEST, "control plane received request {0}", value);
            if (value.hasErrorDetail()) {
              logger.log(Level.FINE, "control plane received nack resource {0}, error {1}",
                  new Object[]{value.getResourceNamesList(), value.getErrorDetail()});
              return;
            }
            if (value.getResourceNamesCount() <= 0) {
              return;
            }
            String resourceType = value.getTypeUrl();
            if (!value.getResponseNonce().isEmpty()
                && !String.valueOf(xdsNonces.get(resourceType)).equals(value.getResponseNonce())) {
              logger.log(Level.FINE, "Resource nonce does not match, ignore.");
              return;
            }
            if (String.valueOf(xdsVersions.get(resourceType)).equals(value.getVersionInfo())) {
              logger.log(Level.FINEST, "control plane received ack for resource: {0}",
                  value.getResourceNamesList());
              return;
            }
            responseObserver.onNext(
                generateResponse(resourceType, value.getResourceNamesList()));
            subscribers.get(resourceType)
                .put(responseObserver, new ArrayList<>(value.getResourceNamesList()));
          }
        });
      }

      @Override
      public void onError(Throwable t) {
        logger.log(Level.FINE, "Control plane error: {0} ", t);
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
        for (String type : subscribers.keySet()) {
          subscribers.get(type).remove(responseObserver);
        }
      }
    };
    return requestObserver;
  }

  //must run in syncContext
  private DiscoveryResponse generateResponse(String resourceType, List<String> resourceNames) {
    DiscoveryResponse.Builder responseBuilder = DiscoveryResponse.newBuilder()
        .setTypeUrl(resourceType)
        .setVersionInfo(String.valueOf(xdsVersions.get(resourceType).getAndDecrement()))
        .setNonce(String.valueOf(xdsNonces.get(resourceType).incrementAndGet()));
    for (String resourceName: resourceNames) {
      if (xdsResources.containsKey(resourceType)
          && xdsResources.get(resourceType).containsKey(resourceName)) {
        responseBuilder.addResources(Any.pack(
            (Message)xdsResources.get(resourceType).get(resourceName),
            resourceType
        ));
      }
    }
    return responseBuilder.build();
  }
}
