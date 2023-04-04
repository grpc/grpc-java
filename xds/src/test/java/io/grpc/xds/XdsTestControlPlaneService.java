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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
* A bidi-stream service that acts as a local xDS Control Plane.
 * It accepts xDS config injection through a method call {@link #setXdsConfig}. Handling AdsStream
 * response or updating xds config are run in syncContext.
 *
 * <p>The service maintains lookup tables:
 * Subscriber table: map from each resource type, to a map from each client to subscribed resource
 * names set.
 * Resources table: store the resources in raw proto message.
 *
 * <p>xDS protocol requires version/nonce to avoid various race conditions. In this impl:
 * Version stores the latest version number per each resource type. It is simply bumped up on each
 * xds config set.
 * Nonce stores the nonce number for each resource type and for each client. Incoming xDS requests
 * share the same proto message type but may at different resources update phases:
 * 1) Original: an initial xDS request.
 * 2) NACK an xDS response.
 * 3) ACK an xDS response.
 * The service is capable of distinguish these cases when handling the request.
 */
final class XdsTestControlPlaneService extends
    AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase {
  private static final Logger logger = Logger.getLogger(XdsTestControlPlaneService.class.getName());

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
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

  private final Map<String, HashMap<String, Message>> xdsResources = new HashMap<>();
  private ImmutableMap<String, Map<StreamObserver<DiscoveryResponse>, Set<String>>> subscribers
      = ImmutableMap.of(
      ADS_TYPE_URL_LDS, new ConcurrentHashMap<StreamObserver<DiscoveryResponse>, Set<String>>(),
      ADS_TYPE_URL_RDS, new ConcurrentHashMap<StreamObserver<DiscoveryResponse>, Set<String>>(),
      ADS_TYPE_URL_CDS, new ConcurrentHashMap<StreamObserver<DiscoveryResponse>, Set<String>>(),
      ADS_TYPE_URL_EDS, new ConcurrentHashMap<StreamObserver<DiscoveryResponse>, Set<String>>());
  private final ImmutableMap<String, AtomicInteger> xdsVersions = ImmutableMap.of(
      ADS_TYPE_URL_LDS, new AtomicInteger(1),
      ADS_TYPE_URL_RDS, new AtomicInteger(1),
      ADS_TYPE_URL_CDS, new AtomicInteger(1),
      ADS_TYPE_URL_EDS, new AtomicInteger(1)
  );
  private final ImmutableMap<String, Map<StreamObserver<DiscoveryResponse>, AtomicInteger>>
      xdsNonces = ImmutableMap.of(
      ADS_TYPE_URL_LDS, new ConcurrentHashMap<StreamObserver<DiscoveryResponse>, AtomicInteger>(),
      ADS_TYPE_URL_RDS, new ConcurrentHashMap<StreamObserver<DiscoveryResponse>, AtomicInteger>(),
      ADS_TYPE_URL_CDS, new ConcurrentHashMap<StreamObserver<DiscoveryResponse>, AtomicInteger>(),
      ADS_TYPE_URL_EDS, new ConcurrentHashMap<StreamObserver<DiscoveryResponse>, AtomicInteger>()
  );


  // treat all the resource types as state-of-the-world, send back all resources of a particular
  // type when any of them change.
  public <T extends Message> void setXdsConfig(final String type, final Map<String, T> resources) {
    logger.log(Level.FINE, "setting config {0} {1}", new Object[]{type, resources});
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        HashMap<String, Message> copyResources =  new HashMap<>(resources);
        xdsResources.put(type, copyResources);
        String newVersionInfo = String.valueOf(xdsVersions.get(type).getAndDecrement());

        for (Map.Entry<StreamObserver<DiscoveryResponse>, Set<String>> entry :
            subscribers.get(type).entrySet()) {
          DiscoveryResponse response = generateResponse(type, newVersionInfo,
              String.valueOf(xdsNonces.get(type).get(entry.getKey()).incrementAndGet()),
              entry.getValue());
          entry.getKey().onNext(response);
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
            String resourceType = value.getTypeUrl();
            if (!value.getResponseNonce().isEmpty()
                && !String.valueOf(xdsNonces.get(resourceType)).equals(value.getResponseNonce())) {
              logger.log(Level.FINE, "Resource nonce does not match, ignore.");
              return;
            }
            Set<String> requestedResourceNames = new HashSet<>(value.getResourceNamesList());
            if (subscribers.get(resourceType).containsKey(responseObserver)
                && subscribers.get(resourceType).get(responseObserver)
                    .equals(requestedResourceNames)) {
              logger.log(Level.FINEST, "control plane received ack for resource: {0}",
                  value.getResourceNamesList());
              return;
            }
            if (!xdsNonces.get(resourceType).containsKey(responseObserver)) {
              xdsNonces.get(resourceType).put(responseObserver, new AtomicInteger(0));
            }
            DiscoveryResponse response = generateResponse(resourceType,
                String.valueOf(xdsVersions.get(resourceType)),
                String.valueOf(xdsNonces.get(resourceType).get(responseObserver)),
                requestedResourceNames);
            responseObserver.onNext(response);
            subscribers.get(resourceType).put(responseObserver, requestedResourceNames);
          }
        });
      }

      @Override
      public void onError(Throwable t) {
        logger.log(Level.FINE, "Control plane error: {0} ", t);
        onCompleted();
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
        for (String type : subscribers.keySet()) {
          subscribers.get(type).remove(responseObserver);
          xdsNonces.get(type).remove(responseObserver);
        }
      }
    };
    return requestObserver;
  }

  //must run in syncContext
  private DiscoveryResponse generateResponse(String resourceType, String version, String nonce,
                                             Set<String> resourceNames) {
    DiscoveryResponse.Builder responseBuilder = DiscoveryResponse.newBuilder()
        .setTypeUrl(resourceType)
        .setVersionInfo(version)
        .setNonce(nonce);
    for (String resourceName: resourceNames) {
      if (xdsResources.containsKey(resourceType)
          && xdsResources.get(resourceType).containsKey(resourceName)) {
        responseBuilder.addResources(Any.pack(xdsResources.get(resourceType).get(resourceName),
            resourceType));
      }
    }
    return responseBuilder.build();
  }
}
