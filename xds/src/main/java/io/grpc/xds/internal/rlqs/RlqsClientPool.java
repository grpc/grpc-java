/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.xds.internal.rlqs;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.InsecureChannelCredentials;
import io.grpc.SynchronizationContext;
import io.grpc.xds.RlqsFilterConfig;
import io.grpc.xds.client.Bootstrapper.RemoteServerInfo;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RlqsClientPool {
  private static final Logger logger = Logger.getLogger(RlqsClientPool.class.getName());

  private static final int DEFAULT_CLEANUP_INTERVAL_SECONDS = 10;

  // TODO(sergiitk): [QUESTION] always in sync context?
  private boolean shutdown;
  private final SynchronizationContext syncContext = new SynchronizationContext((thread, error) -> {
    String message = "Uncaught exception in RlqsClientPool SynchronizationContext. Panic!";
    logger.log(Level.FINE, message, error);
    throw new RlqsPoolSynchronizationException(message, error);
  });

  private final ConcurrentHashMap<String, RlqsClient> clientPool = new ConcurrentHashMap<>();
  Set<String> clientsToShutdown = Sets.newConcurrentHashSet();
  private final ScheduledExecutorService timeService;
  private final int cleanupIntervalSeconds;


  private RlqsClientPool(ScheduledExecutorService scheduler, int cleanupIntervalSeconds) {
    this.timeService = checkNotNull(scheduler, "scheduler");
    checkArgument(cleanupIntervalSeconds >= 0, "cleanupIntervalSeconds < 0");
    this.cleanupIntervalSeconds =
        cleanupIntervalSeconds > 0 ? cleanupIntervalSeconds : DEFAULT_CLEANUP_INTERVAL_SECONDS;
  }

  /** Creates an instance. */
  public static RlqsClientPool newInstance(ScheduledExecutorService scheduler) {
    // TODO(sergiitk): [IMPL] scheduler - consider using GrpcUtil.TIMER_SERVICE.
    // TODO(sergiitk): [IMPL] note that the scheduler has a finite lifetime.
    return new RlqsClientPool(scheduler, 0);
  }

  public void run() {
    Runnable cleanupTask = () -> {
      if (shutdown) {
        return;
      }
      for (String clientHash : clientsToShutdown) {
        clientPool.get(clientHash).shutdown();
        clientPool.remove(clientHash);
      }
      clientsToShutdown.clear();
    };
    syncContext.schedule(cleanupTask, cleanupIntervalSeconds, TimeUnit.SECONDS, timeService);
  }

  public void shutdown() {
    syncContext.execute(() -> {
      shutdown = true;
      logger.log(Level.FINER, "Shutting down RlqsClientPool");
      clientsToShutdown.clear();
      for (String clientHash : clientPool.keySet()) {
        clientPool.get(clientHash).shutdown();
      }
      clientPool.clear();
    });
  }

  public RlqsClient getOrCreateRlqsClient(RlqsFilterConfig config) {
    final SettableFuture<RlqsClient> future = SettableFuture.create();
    final String clientHash = makeRlqsClientHash(config);

    syncContext.execute(() -> {
      if (clientPool.containsKey(clientHash)) {
        future.set(clientPool.get(clientHash));
        return;
      }
      // TODO(sergiitk): [IMPL] get from bootstrap.
      RemoteServerInfo rlqsServer = RemoteServerInfo.create(config.rlqsService().targetUri(),
          InsecureChannelCredentials.create());
      RlqsClient rlqsClient =
          new RlqsClient(rlqsServer, config.domain(), config.bucketMatchers(), clientHash);

      clientPool.put(clientHash, rlqsClient);
      future.set(clientPool.get(clientHash));
    });
    try {
      // TODO(sergiitk): [IMPL] clarify time
      return future.get(1, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      // TODO(sergiitk): [IMPL] handle properly
      throw new RuntimeException(e);
    }
  }

  private String makeRlqsClientHash(RlqsFilterConfig config) {
    // TODO(sergiitk): [DESIGN] the key should be hashed (domain + buckets) merged config?
    // TODO(sergiitk): [IMPL] Hash buckets
    return config.rlqsService().targetUri() + config.domain();
  }

  /**
   * Throws when fail to bootstrap or initialize the XdsClient.
   */
  public static final class RlqsPoolSynchronizationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RlqsPoolSynchronizationException(String message, Throwable cause) {
      super(message, cause);
    }
  }


}
