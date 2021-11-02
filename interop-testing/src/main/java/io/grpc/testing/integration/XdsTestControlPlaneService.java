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


package io.grpc.testing.integration;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import io.grpc.SynchronizationContext;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.listener.v3.Listener;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.grpc.xds.shaded.io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.grpc.xds.shaded.io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.grpc.xds.shaded.io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class XdsTestControlPlaneService extends
    AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase {
  private static final Logger logger = Logger.getLogger(XdsInteropTest.class.getName());

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          logger.log(Level.SEVERE, "Exception!" + e);
        }
      });

  private static final String ADS_TYPE_URL_LDS =
      "type.googleapis.com/envoy.config.listener.v3.Listener";
  private static final String ADS_TYPE_URL_RDS =
      "type.googleapis.com/envoy.config.route.v3.RouteConfiguration";
  private static final String ADS_TYPE_URL_CDS =
      "type.googleapis.com/envoy.config.cluster.v3.Cluster";
  private static final String ADS_TYPE_URL_EDS =
      "type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment";
  private final ImmutableMap<String, Listener> ldsResources;
  private final ImmutableMap<String, RouteConfiguration> rdsResources;
  private final ImmutableMap<String, Cluster> cdsResources;
  private final ImmutableMap<String, ClusterLoadAssignment> edsResources;
  private int ldsVersion = 1;
  private int rdsVersion = 1;
  private int cdsVersion = 1;
  private int edsVersion = 1;
  private int ldsNonce = 0;
  private int rdsNonce = 0;
  private int cdsNonce = 0;
  private int edsNonce = 0;

  /**
   * Create a control plane service for testing, with static xds configurations.
   */
  public XdsTestControlPlaneService(XdsTestControlPlaneConfig config) {
    Map<String, Listener> ldsMap = new HashMap<>();
    for (Listener apiListener: config.apiListener) {
      ldsMap.put(apiListener.getName(), apiListener);
    }
    for (Listener tcpListener: config.tcpListener) {
      ldsMap.put(tcpListener.getName(), tcpListener);
    }
    this.ldsResources = ImmutableMap.copyOf(ldsMap);

    Map<String, RouteConfiguration> rdsMap = new HashMap<>();
    for (RouteConfiguration rds:config.rds) {
      rdsMap.put(rds.getName(), rds);
    }
    this.rdsResources = ImmutableMap.copyOf(rdsMap);

    Map<String, Cluster> cdsMap = new HashMap<>();
    for (Cluster cds:config.cds) {
      cdsMap.put(cds.getName(), cds);
    }
    this.cdsResources = ImmutableMap.copyOf(cdsMap);

    Map<String, ClusterLoadAssignment> edsMap = new HashMap<>();
    for (ClusterLoadAssignment eds:config.eds) {
      edsMap.put(eds.getClusterName(), eds);
    }
    this.edsResources = ImmutableMap.copyOf(edsMap);
    logger.log(Level.FINER, "control plane config created. "
            + "Dumping resources lds:{0},\nrds:{1},\ncds:{2},\neds:{3}",
        new Object[]{ldsMap, rdsMap, cdsMap, edsMap});
  }

  public static class XdsTestControlPlaneConfig {
    ImmutableList<Listener> tcpListener;
    ImmutableList<Listener> apiListener;
    ImmutableList<RouteConfiguration> rds;
    ImmutableList<Cluster> cds;
    ImmutableList<ClusterLoadAssignment> eds;

    /**
     * Provides control plane xds configurations.
     */
    public XdsTestControlPlaneConfig(List<Listener> tcpListener,
                                     List<Listener> apiListener,
                                     List<RouteConfiguration> rds,
                                     List<Cluster> cds,
                                     List<ClusterLoadAssignment> eds) {
      this.tcpListener = ImmutableList.copyOf(tcpListener);
      this.apiListener = ImmutableList.copyOf(apiListener);
      this.rds = ImmutableList.copyOf(rds);
      this.cds = ImmutableList.copyOf(cds);
      this.eds = ImmutableList.copyOf(eds);
    }
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
            switch (value.getTypeUrl()) {
              case ADS_TYPE_URL_LDS:
                if (!value.getResponseNonce().isEmpty()
                    && !String.valueOf(ldsNonce).equals(value.getResponseNonce())) {
                  logger.log(Level.FINE, "lds resource nonce does not match, ignore.");
                  return;
                }
                if (String.valueOf(ldsVersion).equals(value.getVersionInfo())) {
                  logger.log(Level.FINEST, "control plane received ack for lds resource: {0}",
                      value.getResourceNamesList());
                  return;
                }
                DiscoveryResponse.Builder responseBuilder = DiscoveryResponse.newBuilder()
                    .setTypeUrl(ADS_TYPE_URL_LDS)
                    .setVersionInfo(String.valueOf(ldsVersion++))
                    .setNonce(String.valueOf(++ldsNonce));
                for (String ldsName: value.getResourceNamesList()) {
                  if (ldsResources.containsKey(ldsName)) {
                    responseBuilder.addResources(Any.pack(
                        ldsResources.get(ldsName),
                        ADS_TYPE_URL_LDS
                    ));
                  }
                }
                responseObserver.onNext(responseBuilder.build());
                break;
              case ADS_TYPE_URL_RDS:
                if (!value.getResponseNonce().isEmpty()
                    && !String.valueOf(rdsNonce).equals(value.getResponseNonce())) {
                  logger.log(Level.FINE, "rds resource nonce does not match, ignore.");
                  return;
                }
                if (String.valueOf(rdsVersion).equals(value.getVersionInfo())) {
                  logger.log(Level.FINEST, "control plane received ack for rds resource: {0}",
                      value.getResourceNamesList());
                  return;
                }
                responseBuilder = DiscoveryResponse.newBuilder()
                    .setTypeUrl(ADS_TYPE_URL_RDS)
                    .setVersionInfo(String.valueOf(rdsVersion++))
                    .setNonce(String.valueOf(++rdsNonce));
                for (String rdsName: value.getResourceNamesList()) {
                  if (rdsResources.containsKey(rdsName)) {
                    responseBuilder.addResources(Any.pack(
                        rdsResources.get(rdsName),
                        ADS_TYPE_URL_RDS
                    ));
                  }
                }
                responseObserver.onNext(responseBuilder.build());
                break;
              case ADS_TYPE_URL_CDS:
                if (!value.getResponseNonce().isEmpty()
                    && !String.valueOf(cdsNonce).equals(value.getResponseNonce())) {
                  logger.log(Level.FINE, "cds resource nonce does not match, ignore.");
                  return;
                }
                if (String.valueOf(cdsVersion).equals(value.getVersionInfo())) {
                  logger.log(Level.FINEST, "control plane received ack for cds resource: {0}",
                      value.getResourceNamesList());
                  return;
                }
                responseBuilder = DiscoveryResponse.newBuilder()
                    .setTypeUrl(ADS_TYPE_URL_CDS)
                    .setVersionInfo(String.valueOf(cdsVersion++))
                    .setNonce(String.valueOf(++cdsNonce));
                for (String cdsName: value.getResourceNamesList()) {
                  if (cdsResources.containsKey(cdsName)) {
                    responseBuilder.addResources(Any.pack(
                        cdsResources.get(cdsName),
                        ADS_TYPE_URL_CDS
                    ));
                  }
                }
                responseObserver.onNext(responseBuilder.build());
                break;
              case ADS_TYPE_URL_EDS:
                if (!value.getResponseNonce().isEmpty()
                    && !String.valueOf(edsNonce).equals(value.getResponseNonce())) {
                  logger.log(Level.FINE, "eds resource nonce does not match, ignore.");
                  return;
                }
                if (String.valueOf(edsVersion).equals(value.getVersionInfo())) {
                  logger.log(Level.FINEST, "control plane received ack for eds resource: {0}",
                      value.getResourceNamesList());
                  return;
                }
                responseBuilder = DiscoveryResponse.newBuilder()
                    .setTypeUrl(ADS_TYPE_URL_EDS)
                    .setVersionInfo(String.valueOf(edsVersion++))
                    .setNonce(String.valueOf(++edsNonce));
                for (String edsName: value.getResourceNamesList()) {
                  if (edsResources.containsKey(edsName)) {
                    responseBuilder.addResources(Any.pack(
                            edsResources.get(value.getResourceNames(0)),
                            ADS_TYPE_URL_EDS
                    ));
                  }
                }
                responseObserver.onNext(responseBuilder.build());
                break;
              default:
                logger.log(Level.WARNING, "unrecognized typeUrl in discoveryRequest: {0}",
                    value.getTypeUrl());
            }
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
      }
    };
    return requestObserver;
  }
}
