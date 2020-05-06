/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.rls;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

/** An LruCache is a cache with least recently used eviction. */
interface LruCache<K, V> {

  /**
   * Populates a cache entry. If the cache entry for given key already exists, the value will be
   * replaced to the new value.
   *
   * @return the previous value associated with key, otherwise {@code null}
   */
  @Nullable
  V cache(K key, V value);

  /**
   * Returns cached value for given key if exists, otherwise {@code null}. This operation doesn't
   * return already expired cache entry.
   */
  @Nullable
  @CheckReturnValue
  V read(K key);

  /**
   * Invalidates an entry for given key if exists. This operation will trigger {@link
   * EvictionListener} with {@link EvictionType#EXPLICIT}.
   *
   * @return the previous value associated with key, otherwise {@code null}
   */
  @Nullable
  V invalidate(K key);

  /**
   * Invalidates cache entries for given keys. This operation will trigger {@link EvictionListener}
   * with {@link EvictionType#EXPLICIT}.
   */
  void invalidateAll(Iterable<K> keys);

  /** Returns {@code true} if given key is cached. */
  @CheckReturnValue
  boolean hasCacheEntry(K key);

  /**
   * Returns the estimated number of entry of the cache. Note that the size can be larger than its
   * true size, because there might be already expired cache.
   */
  @CheckReturnValue
  int estimatedSize();

  /** Closes underlying resources. */
  void close();

  /** A Listener notifies cache eviction events. */
  interface EvictionListener<K, V> {

    /**
     * Notifies the listener when any cache entry is evicted. Implementation can assume that this
     * method is called serially. Implementation should be non blocking, for long running task
     * consider offloading the task to {@link java.util.concurrent.Executor}.
     */
    void onEviction(K key, V value, EvictionType cause);
  }

  /** Type of cache eviction. */
  enum EvictionType {
    /** Explicitly removed by user. */
    EXPLICIT,
    /** Evicted due to size limit. */
    SIZE,
    /** Evicted due to entry expired. */
    EXPIRED,
    /** Removed due to error. */
    ERROR,
    /** Evicted by replacement. */
    REPLACED
  }
}
