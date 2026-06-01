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

package io.grpc.xds;

import com.google.common.base.Preconditions;
import io.grpc.Internal;
import io.grpc.ManagedChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Manages generic reference-counted shared resources for xDS filters.
 *
 * <p>Similar to {@code io.grpc.xds.internal.security.ReferenceCountingMap}, but provides
 * additional lifecycle management ({@link #close()}) and a simpler key-only
 * {@link #release(Object)} API designed for xDS filter state cleanup tasks.
 *
 * <h3>Requirements</h3>
 * <ul>
 *   <li>{@link #acquire} and {@link #close} must be called from the same serialized context
 *       (e.g., {@code SynchronizationContext}). They must never race each other.</li>
 *   <li>{@link #release} may be called from any thread.</li>
 *   <li>Managed resources ({@link ResourceCloseable#close}) must be thread-safe, as they may
 *       be invoked from any thread when the last reference is released.</li>
 *   <li>Keys are canonical resource identifiers: a given key always maps to the same logical
 *       resource. The manager will never hold two distinct resources for the same key.</li>
 * </ul>
 */
@Internal
public final class SharedResourceManager<K, V extends SharedResourceManager.ResourceCloseable> {

  /**
   * An AutoCloseable resource that explicitly guarantees its close operation
   * will not throw checked exceptions.
   */
  public interface ResourceCloseable extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Adapts {@link ManagedChannel} to {@link ResourceCloseable} for management by
   * {@link SharedResourceManager}.
   */
  public static final class ManagedChannelResource implements ResourceCloseable {
    private final ManagedChannel channel;

    public ManagedChannelResource(ManagedChannel channel) {
      this.channel = Preconditions.checkNotNull(channel, "channel");
    }

    @Override
    public void close() {
      channel.shutdown();
    }

    public ManagedChannel getChannel() {
      return channel;
    }
  }

  /**
   * An internal pure reference-counting container managing a stateful ResourceCloseable.
   */
  @ThreadSafe
  static final class SharedResource<T extends ResourceCloseable> {
    private final T resource;
    private final AtomicInteger refCount = new AtomicInteger(1);

    SharedResource(T resource) {
      this.resource = Preconditions.checkNotNull(resource, "resource");
    }

    /**
     * Retains the resource. Returns false if the resource has hit 0 and is being closed.
     */
    boolean retain() {
      int count;
      do {
        count = refCount.get();
        if (count == 0) {
          return false;
        }
      } while (!refCount.compareAndSet(count, count + 1));
      return true;
    }

    /**
     * Decrements reference count. Closes underlying resource if count hits 0.
     * @return true if the count reached 0 and the resource was closed; false otherwise.
     */
    boolean release() {
      int count;
      do {
        count = refCount.get();
        if (count <= 0) {
          throw new AssertionError("SharedResourceManager reference count is already 0");
        }
      } while (!refCount.compareAndSet(count, count - 1));
      if (count == 1) {
        resource.close();
        return true;
      }
      return false;
    }

    T get() {
      return resource;
    }

    int getRefCount() {
      return refCount.get();
    }
  }

  private final ConcurrentMap<K, SharedResource<V>> resources = new ConcurrentHashMap<>();
  private final Function<K, V> resourceCreator;
  private boolean closed;

  public SharedResourceManager(Function<K, V> resourceCreator) {
    this.resourceCreator = resourceCreator;
  }

  /**
   * Acquires a resource for the given key, incrementing its reference count.
   *
   * <p>Must be called from the {@code SynchronizationContext}.
   */
  public V acquire(K key) {
    Preconditions.checkState(!closed, "SharedResourceManager is closed");
    SharedResource<V> shared = resources.computeIfAbsent(key,
        k -> new SharedResource<>(resourceCreator.apply(k)));
    Preconditions.checkState(shared.retain(),
        "retain() failed; resource was concurrently closed");
    return shared.get();
  }

  /**
   * Releases a resource for the given key, decrementing its reference count.
   * Atomically evicts the entry if the reference count reaches 0, ensuring that no
   * stale (dead) entry is ever visible to a concurrent {@link #acquire}.
   *
   * <p>Thread-safe: may be called from any thread. The decrement and eviction are performed
   * atomically under the {@link ConcurrentHashMap} bucket lock via {@code compute()}.
   *
   * <p>This API takes only a key (not the resource value) because keys are canonical
   * resource identifiers (see class-level requirements).
   *
   * @return true if the resource was closed; false otherwise.
   */
  public boolean release(K key) {
    boolean[] closed = {false};
    Throwable[] thrown = {null};
    resources.compute(key, (k, shared) -> {
      if (shared == null) {
        return null;
      }
      try {
        if (shared.release()) {
          closed[0] = true;
          return null; // evict atomically
        }
      } catch (Throwable t) {
        thrown[0] = t;
        return null; // evict on error too
      }
      return shared;
    });
    if (thrown[0] != null) {
      throwIfUnchecked(thrown[0]);
    }
    return closed[0];
  }

  @SuppressWarnings("unchecked")
  private static void throwIfUnchecked(Throwable t) {
    if (t instanceof RuntimeException) {
      throw (RuntimeException) t;
    }
    if (t instanceof Error) {
      throw (Error) t;
    }
    throw new RuntimeException(t);
  }

  /**
   * Removes all entries from the cache and releases the manager's creation reference for each.
   *
   * <p>This performs a single {@code release()} per entry, decrementing the manager's own
   * reference count contribution (the initial refCount=1 from creation). If in-flight RPCs
   * still hold references, the underlying resource remains open until those references are
   * released. This avoids pulling resources out from under active operations.
   *
   * <p>Must be called from the {@code SynchronizationContext}.
   */
  public void close() {
    closed = true;
    for (K key : resources.keySet()) {
      SharedResource<V> shared = resources.remove(key);
      if (shared != null) {
        try {
          shared.release();
        } catch (Throwable t) {
          // Ignore exceptions during final close-all to ensure we try to close other resources
        }
      }
    }
  }
}
