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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Throwables;
import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InternalLogId;
import io.grpc.SynchronizationContext;
import io.grpc.xds.RlqsFilterConfig;
import io.grpc.xds.client.Bootstrapper.RemoteServerInfo;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsLogger.XdsLogLevel;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;

public final class RlqsCache {
  // TODO(sergiitk): [QUESTION] always in sync context?
  private volatile boolean shutdown = false;

  private final XdsLogger logger;
  private final SynchronizationContext syncContext;

  private final ConcurrentMap<Long, RlqsFilterState> filterStateCache = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler;


  private RlqsCache(ScheduledExecutorService scheduler) {
    this.scheduler = checkNotNull(scheduler, "scheduler");
    // TODO(sergiitk): should be filter name?
    logger = XdsLogger.withLogId(InternalLogId.allocate(this.getClass(), null));

    syncContext = new SynchronizationContext((thread, error) -> {
      String message = "Uncaught exception in RlqsCache SynchronizationContext. Panic!";
      logger.log(XdsLogLevel.DEBUG,
          message + " {0} \nTrace:\n {1}", error, Throwables.getStackTraceAsString(error));
      throw new RlqsCacheSynchronizationException(message, error);
    });
  }

  /** Creates an instance. */
  public static RlqsCache newInstance(ScheduledExecutorService scheduler) {
    // TODO(sergiitk): [IMPL] scheduler - consider using GrpcUtil.TIMER_SERVICE.
    // TODO(sergiitk): [IMPL] note that the scheduler has a finite lifetime.
    return new RlqsCache(scheduler);
  }

  public void shutdown() {
    if (shutdown) {
      return;
    }
    syncContext.execute(() -> {
      shutdown = true;
      logger.log(XdsLogLevel.DEBUG, "Shutting down RlqsCache");
      for (long configHash : filterStateCache.keySet()) {
        filterStateCache.get(configHash).shutdown();
      }
      filterStateCache.clear();
      shutdown = false;
    });
  }

  public void shutdownFilterState(RlqsFilterConfig oldConfig) {
    // TODO(sergiitk): shutdown one
    // make it async.
  }

  public RlqsFilterState getOrCreateFilterState(final RlqsFilterConfig config) {
    // TODO(sergiitk): handle being shut down.
    long configHash = hashFilterConfig(config);
    return filterStateCache.computeIfAbsent(configHash, k -> newFilterState(k, config));
  }

  private RlqsFilterState newFilterState(long configHash, RlqsFilterConfig config) {
    // TODO(sergiitk): [IMPL] get channel creds from the bootstrap.
    ChannelCredentials creds = InsecureChannelCredentials.create();
    return new RlqsFilterState(
        RemoteServerInfo.create(config.rlqsService().targetUri(), creds),
        config.domain(),
        config.bucketMatchers(),
        configHash,
        scheduler);
  }

  private long hashFilterConfig(RlqsFilterConfig config) {
    // TODO(sergiitk): [QUESTION] better name? - ask Eric.
    // TODO(sergiitk): [DESIGN] the key should be hashed (domain + buckets) merged config?
    // TODO(sergiitk): [IMPL] Hash buckets
    int k1 = Objects.hash(config.rlqsService().targetUri(), config.domain());
    int k2;
    if (config.bucketMatchers() == null) {
      k2 = 0x42c0ffee;
    } else {
      k2 = config.bucketMatchers().hashCode();
    }
    return Long.rotateLeft(Integer.toUnsignedLong(k1), 32) + Integer.toUnsignedLong(k2);
  }

  /**
   * Throws when fail to bootstrap or initialize the XdsClient.
   */
  public static final class RlqsCacheSynchronizationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RlqsCacheSynchronizationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
