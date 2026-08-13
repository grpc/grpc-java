/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.autosharding;

import com.google.cloud.autosharding.v1main.AssignmentChunk;
import com.google.cloud.autosharding.v1main.AssignmentMetadata;
import com.google.cloud.autosharding.v1main.DynamicShardingServiceGrpc;
import com.google.cloud.autosharding.v1main.EndpointState;
import com.google.cloud.autosharding.v1main.InitialClientConfig;
import com.google.cloud.autosharding.v1main.PerSliceEndpointState;
import com.google.cloud.autosharding.v1main.SliceAssignment;
import com.google.cloud.autosharding.v1main.WatchShardingAssignmentRequest;
import com.google.cloud.autosharding.v1main.WatchShardingAssignmentResponse;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ShardingClient {
  private static final Logger logger = Logger.getLogger(ShardingClient.class.getName());

  interface Callback {
    void onAssignmentReceived(
        List<SliceAssignment> sliceAssignments,
        List<EndpointState> endpoints,
        long generation);

    void onError(Throwable t);
  }

  private final Channel channel;
  private final String target;
  private final String clientUuid;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService scheduledExecutorService;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Callback callback;

  private Context.CancellableContext cancellableContext;
  private StreamObserver<WatchShardingAssignmentRequest> requestStream;
  private long currentGeneration = 0;
  private BackoffPolicy backoffPolicy;
  private SynchronizationContext.ScheduledHandle retryTimer;
  private boolean stopped = false;

  // State for assembling chunks
  private final List<SliceAssignment> currentSliceAssignments = new ArrayList<>();
  private final List<EndpointState> currentEndpoints = new ArrayList<>();

  ShardingClient(
      Channel channel,
      String target,
      long initialGeneration,
      SynchronizationContext syncContext,
      ScheduledExecutorService scheduledExecutorService,
      Callback callback) {
    this(
        channel,
        target,
        initialGeneration,
        syncContext,
        scheduledExecutorService,
        new ExponentialBackoffPolicy.Provider(),
        callback);
  }

  ShardingClient(
      Channel channel,
      String target,
      long initialGeneration,
      SynchronizationContext syncContext,
      ScheduledExecutorService scheduledExecutorService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Callback callback) {
    this.channel = channel;
    this.target = target;
    this.clientUuid = UUID.randomUUID().toString();
    this.currentGeneration = initialGeneration;
    this.syncContext = syncContext;
    this.scheduledExecutorService = scheduledExecutorService;
    this.backoffPolicyProvider = backoffPolicyProvider;
    this.callback = callback;
  }

  void start() {
    if (stopped) {
      return;
    }
    closeStream();

    cancellableContext = Context.current().withCancellation();
    cancellableContext.run(() -> {
      DynamicShardingServiceGrpc.DynamicShardingServiceStub stub =
          DynamicShardingServiceGrpc.newStub(channel).withWaitForReady();

      requestStream = stub.watchShardingAssignment(
          new StreamObserver<WatchShardingAssignmentResponse>() {
            @Override
            public void onNext(WatchShardingAssignmentResponse response) {
              syncContext.execute(() -> handleResponse(response));
            }

            @Override
            public void onError(Throwable t) {
              syncContext.execute(() -> handleError(t));
            }

            @Override
            public void onCompleted() {
              syncContext.execute(() -> handleError(
                  Status.UNAVAILABLE.withDescription("Server closed stream").asRuntimeException()));
            }
          });

      InitialClientConfig initConfig = InitialClientConfig.newBuilder()
          .setTarget(target)
          .setClientUuid(clientUuid)
          .setCurrentGeneration(currentGeneration)
          .build();

      requestStream.onNext(WatchShardingAssignmentRequest.newBuilder().setInit(initConfig).build());
    });
  }

  private void handleResponse(WatchShardingAssignmentResponse response) {
    if (response.hasChunk()) {
      AssignmentChunk chunk = response.getChunk();
      currentSliceAssignments.addAll(chunk.getSliceAssignmentsList());
      currentEndpoints.addAll(chunk.getEndpointsList());
    } else if (response.hasMetadata()) {
      List<SliceAssignment> assembledSlices = new ArrayList<>(currentSliceAssignments);
      List<EndpointState> assembledEndpoints = new ArrayList<>(currentEndpoints);
      
      currentSliceAssignments.clear();
      currentEndpoints.clear();

      // Validate assignment per gRFC A119
      if (!validateAssignment(assembledSlices, assembledEndpoints)) {
        logger.log(
            Level.WARNING, "Assignment validation failed. Terminating stream to reconnect.");
        handleError(
            Status.INTERNAL.withDescription(
                "Assignment validation failed: invalid key ranges or endpoint indices")
                .asRuntimeException());
        return;
      }

      // Reset backoff state upon receiving a good logical assignment from the server
      backoffPolicy = null;
      AssignmentMetadata metadata = response.getMetadata();
      currentGeneration = metadata.getGeneration();
      
      callback.onAssignmentReceived(assembledSlices, assembledEndpoints, currentGeneration);
    } else if (response.hasConfig()) {
      // Ignore for now as per gRFC
    } else {
      logger.log(Level.WARNING, "Received unknown or empty response from sharding service");
    }
  }

  private static boolean validateAssignment(
      List<SliceAssignment> slices, List<EndpointState> endpoints) {
    if (slices.isEmpty()) {
      return false;
    }
    int totalEndpoints = endpoints.size();

    // 1. Ensure all endpoint indices are valid
    for (SliceAssignment sa : slices) {
      for (PerSliceEndpointState pse : sa.getEndpointsList()) {
        int epIdx = pse.getEndpointIndex();
        if (epIdx < 0 || epIdx >= totalEndpoints) {
          return false;
        }
      }
    }

    // 2. Ensure no gaps in key ranges and covers full range ["" .. ""]
    List<SliceAssignment> sorted = new ArrayList<>(slices);
    sorted.sort(
        Comparator.comparing(
            sa -> sa.getSlice().getStartKeyInclusive(),
            ByteString.unsignedLexicographicalComparator()));

    // First slice must start at empty ByteString (start of keyspace)
    if (!sorted.get(0).getSlice().getStartKeyInclusive().isEmpty()) {
      return false;
    }

    for (int i = 0; i < sorted.size() - 1; i++) {
      ByteString currentEnd = sorted.get(i).getSlice().getEndKeyExclusive();
      ByteString nextStart = sorted.get(i + 1).getSlice().getStartKeyInclusive();
      // If end_key is unset (empty), this slice extends to the largest allowed key.
      // It cannot have subsequent slices.
      if (currentEnd.isEmpty()) {
        return false;
      }
      if (!currentEnd.equals(nextStart)) {
        return false; // Gap or overlap
      }
    }

    // Last slice's end_key must be empty (sentinel indicating end of keyspace)
    if (!sorted.get(sorted.size() - 1).getSlice().getEndKeyExclusive().isEmpty()) {
      return false;
    }

    return true;
  }

  private void closeStream() {
    if (cancellableContext != null) {
      cancellableContext.cancel(
          Status.CANCELLED.withDescription("Stream closed by client").asRuntimeException());
      cancellableContext = null;
    }
    requestStream = null;
  }

  private void handleError(Throwable t) {
    currentSliceAssignments.clear();
    currentEndpoints.clear();
    closeStream();
    callback.onError(t);
    if (!stopped) {
      scheduleReconnect();
    }
  }

  private void scheduleReconnect() {
    if (backoffPolicy == null) {
      backoffPolicy = backoffPolicyProvider.get();
    }
    long delayNanos = backoffPolicy.nextBackoffNanos();
    logger.log(Level.INFO, "ShardingClient stream disconnected. Retrying in {0} ns", delayNanos);
    if (retryTimer != null) {
      retryTimer.cancel();
    }
    retryTimer =
        syncContext.schedule(
            this::start,
            delayNanos,
            TimeUnit.NANOSECONDS,
            scheduledExecutorService);
  }

  void stop() {
    stopped = true;
    if (retryTimer != null) {
      retryTimer.cancel();
      retryTimer = null;
    }
    closeStream();
  }
}
