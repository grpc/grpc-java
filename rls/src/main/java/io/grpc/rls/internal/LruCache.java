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

package io.grpc.rls.internal;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

/** An LruCache is a cache interface implementing least recently used eviction. */
interface LruCache<K, V> {

  /** Populates a cache entry. If the key already exists, it will replace the entry. */
  @Nullable
  V cache(K key, V value);

  /**
   * Returns cached value for given key if exists. This operation doesn't return already expired
   * cache entry.
   */
  @Nullable
  @CheckReturnValue
  V read(K key);

  /**
   * Invalidates an entry for given key if exists. This operation will trigger {@link
   * EvictionListener} with {@link EvictionType#EXPLICIT}.
   */
  @Nullable
  V invalidate(K key);


  /**
   * Invalidates an entry for given key if exists. This operation will trigger {@link
   * EvictionListener} with given cause.
   */
  @Nullable
  V invalidate(K key, EvictionType cause);

  /**
   * Invalidates cache entries for given keys. This operation will trigger {@link EvictionListener}
   * with {@link EvictionType#EXPLICIT}.
   */
  void invalidateAll(Iterable<K> keys);

  /**
   * Invalidates cache entries for given keys. This operation will trigger {@link EvictionListener}
   * with given cause.
   */
  void invalidateAll(Iterable<K> keys, EvictionType cause);

  /** Returns {@code true} if given key is cached. */
  @CheckReturnValue
  boolean hasCacheEntry(K key);

  /**
   * Returns the estimated number of entry of the cache. Note that the size can be larger than its
   * true size because there might be already expired cache.
   */
  @CheckReturnValue
  int estimatedSize();

  /** Performs any underlying resource if exists. */
  void close();

  /** A listener to notify when a cache entry is evicted. */
  interface EvictionListener<K, V> {

    /**
     * Notifies the listener when any cache entry is evicted. Implementation can assume that this
     * method is not called concurrently. Implementation should be fast and non blocking, for long
     * running task consider offloading to {@link java.util.concurrent.Executor}.
     */
    void onEviction(K key, V value, EvictionType cause);
  }

  /** An EvictionType indicates the cause of eviction from {@link LinkedHashLruCache}. */
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
