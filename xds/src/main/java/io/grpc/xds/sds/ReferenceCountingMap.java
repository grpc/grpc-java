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

package io.grpc.xds.sds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.LogExceptionRunnable;
import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A map for managing reference-counted shared resources.
 *
 * <p>This class is used to hold shared resources as reference counted objects and manages their
 * life-cycle.
 *
 * <p>A resource is identified by the reference of a {@link ResourceDefinition} object, which
 * provides the getKey() and create() methods. The key from getKey() maps to the object stored in
 * the map and create() is used to create the resource (object) in case it is not present in the
 * map.
 *
 * <p>Resources are ref-counted and closed by calling {@link Closeable#close()} after a delay when
 * the ref-count reaches zero. This delay is specified as {@code destroyDelaySeconds} argument in
 * the constructors. The default is 1 second with the no-arg constructor.
 *
 * @param <K> Key type for the map
 * @param <T> Value type for the map (needs to extend {@link Closeable}
 */
@ThreadSafe
final class ReferenceCountingMap<K, T extends Closeable> {

  private static final Logger logger = Logger.getLogger(ReferenceCountingMap.class.getName());
  private final long destroyDelaySeconds;
  private final HashMap<K, Instance<T>> instances;
  private final ScheduledExecutorFactory destroyerFactory;
  private ScheduledExecutorService destroyer;

  ReferenceCountingMap() {
    this(/* destroyDelaySeconds= */ 1L);
  }

  ReferenceCountingMap(long destroyDelaySeconds) {
    this(
        new ScheduledExecutorFactory() {
          @Override
          public ScheduledExecutorService createScheduledExecutor() {
            return Executors.newSingleThreadScheduledExecutor(
                GrpcUtil.getThreadFactory("grpc-refcount-destroyer-%d", true));
          }
        },
        destroyDelaySeconds);
  }

  ReferenceCountingMap(ScheduledExecutorFactory destroyerFactory, long destroyDelaySeconds) {
    this.destroyerFactory = destroyerFactory;
    instances = new HashMap<>();
    this.destroyDelaySeconds = destroyDelaySeconds;
  }

  /**
   * Try to get an existing instance of a given resource. If an instance does not exist, create a
   * new one with the given ResourceDefinition.
   */
  public T get(ResourceDefinition<K, T> resourceDefinition) {
    checkNotNull(resourceDefinition, "resourceDefinition");
    return getInternal(resourceDefinition);
  }

  /**
   * Releases an instance of the given resource.
   *
   * <p>The instance must have been obtained from {@link #get(ResourceDefinition)}. Otherwise will
   * throw IllegalArgumentException.
   *
   * <p>Caller must not release a reference more than once. It's advised that you clear the
   * reference to the instance with the null returned by this method.
   *
   * @param key the key that identifies the shared resource
   * @param value the resource to be released
   * @return a null which the caller can use to clear the reference to that instance.
   */
  public T release(final K key, final T value) {
    checkNotNull(key, "key");
    checkNotNull(value, "value");
    return releaseInternal(key, value);
  }

  private synchronized T getInternal(ResourceDefinition<K, T> resource) {
    Instance<T> instance = instances.get(resource.getKey());
    if (instance == null) {
      instance = new Instance<>(resource.create());
      instances.put(resource.getKey(), instance);
    }
    if (instance.destroyTask != null) {
      instance.destroyTask.cancel(false);
      instance.destroyTask = null;
    }
    instance.refcount++;
    return instance.payload;
  }

  private synchronized T releaseInternal(final K key, final T instance) {
    final Instance<T> cached = instances.get(key);
    if (cached == null) {
      throw new IllegalArgumentException("No cached instance found for " + key);
    }
    Preconditions.checkArgument(instance == cached.payload, "Releasing the wrong instance");
    Preconditions.checkState(cached.refcount > 0, "Refcount has already reached zero");
    cached.refcount--;
    if (cached.refcount == 0) {
      Preconditions.checkState(cached.destroyTask == null, "Destroy task already scheduled");
      // Schedule a delayed task to destroy the resource.
      if (destroyer == null) {
        destroyer = destroyerFactory.createScheduledExecutor();
      }
      cached.destroyTask =
          destroyer.schedule(
              new LogExceptionRunnable(
                  new Runnable() {
                    @Override
                    public void run() {
                      synchronized (ReferenceCountingMap.this) {
                        // Refcount may have gone up since the task was scheduled. Re-check it.
                        if (cached.refcount == 0) {
                          try {
                            cached.payload.close();
                          } catch (IOException e) {
                            logger.log(Level.SEVERE, "calling close on payload", e);
                          } finally {
                            instances.remove(key);
                            if (instances.isEmpty()) {
                              destroyer.shutdown();
                              destroyer = null;
                            }
                          }
                        }
                      }
                    }
                  }),
              destroyDelaySeconds,
              TimeUnit.SECONDS);
    }
    // Always returning null
    return null;
  }

  /** Defines a resource: identified using the key and the way to create it. */
  public interface ResourceDefinition<K, T> {

    /** Create a new instance of the resource. */
    T create();

    /** returns the key to be used to identify the resource. */
    K getKey();
  }

  interface ScheduledExecutorFactory {

    ScheduledExecutorService createScheduledExecutor();
  }

  private static class Instance<T extends Closeable> {

    final T payload;
    int refcount;
    ScheduledFuture<?> destroyTask;

    Instance(T payload) {
      this.payload = payload;
      this.refcount = 0;
    }
  }
}
