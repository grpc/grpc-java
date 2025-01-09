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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.MoreObjects;
import com.google.common.base.Ticker;
import com.google.errorprone.annotations.CheckReturnValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A LinkedHashLruCache implements least recently used caching where it supports access order lru
 * cache eviction while allowing entry level expiration time. When the cache reaches max capacity,
 * LruCache try to remove up to one already expired entries. If it doesn't find any expired entries,
 * it will remove based on access order of entry. To proactively clean up expired entries, call
 * {@link #cleanupExpiredEntries()} (e.g., via a recurring timer).
 */
abstract class LinkedHashLruCache<K, V> implements LruCache<K, V> {

  private final LinkedHashMap<K, SizedValue> delegate;
  private final Ticker ticker;
  private final EvictionListener<K, SizedValue> evictionListener;
  private long estimatedSizeBytes;
  private long estimatedMaxSizeBytes;

  LinkedHashLruCache(
      final long estimatedMaxSizeBytes,
      @Nullable final EvictionListener<K, V> evictionListener,
      final Ticker ticker) {
    checkState(estimatedMaxSizeBytes > 0, "max estimated cache size should be positive");
    this.estimatedMaxSizeBytes = estimatedMaxSizeBytes;
    this.evictionListener = new SizeHandlingEvictionListener(evictionListener);
    this.ticker = checkNotNull(ticker, "ticker");
    delegate = new LinkedHashMap<K, SizedValue>(
        // rough estimate or minimum hashmap default
        Math.max((int) (estimatedMaxSizeBytes / 1000), 16),
        /* loadFactor= */ 0.75f,
        /* accessOrder= */ true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<K, SizedValue> eldest) {
        if (estimatedSizeBytes <= LinkedHashLruCache.this.estimatedMaxSizeBytes) {
          return false;
        }

        // first, remove at most 1 expired entry
        boolean removed = cleanupExpiredEntries(1, ticker.read());
        // handles size based eviction if necessary no expired entry
        boolean shouldRemove = !removed
            && shouldInvalidateEldestEntry(eldest.getKey(), eldest.getValue().value, ticker.read());
        if (shouldRemove) {
          // remove entry by us to make sure lruIterator and cache is in sync
          LinkedHashLruCache.this.invalidate(eldest.getKey(), EvictionType.SIZE);
        }
        return false;
      }
    };
  }

  /**
   * Determines if the eldest entry should be kept or not when the cache size limit is reached. Note
   * that LruCache is access level and the eldest is determined by access pattern.
   */
  @SuppressWarnings("unused")
  protected boolean shouldInvalidateEldestEntry(K eldestKey, V eldestValue, long now) {
    return true;
  }

  /** Determines if the entry is already expired or not. */
  protected abstract boolean isExpired(K key, V value, long nowNanos);

  /**
   * Returns estimated size of entry to keep track. If it always returns 1, the max size bytes
   * behaves like max number of entry (default behavior).
   */
  @SuppressWarnings("unused")
  protected int estimateSizeOf(K key, V value) {
    return 1;
  }

  protected long estimatedMaxSizeBytes() {
    return estimatedMaxSizeBytes;
  }

  /** Updates size for given key if entry exists. It is useful if the cache value is mutated. */
  public void updateEntrySize(K key) {
    SizedValue entry = readInternal(key);
    if (entry == null) {
      return;
    }
    int prevSize = entry.size;
    int newSize = estimateSizeOf(key, entry.value);
    entry.size = newSize;
    estimatedSizeBytes += newSize - prevSize;
  }

  /**
   * Returns estimated cache size bytes. Each entry size is calculated by {@link
   * #estimateSizeOf(java.lang.Object, java.lang.Object)}.
   */
  public long estimatedSizeBytes() {
    return estimatedSizeBytes;
  }

  @Override
  @Nullable
  public final V cache(K key, V value) {
    checkNotNull(key, "key");
    checkNotNull(value, "value");
    SizedValue existing;
    int size = estimateSizeOf(key, value);
    estimatedSizeBytes += size;
    existing = delegate.put(key, new SizedValue(size, value));
    if (existing != null) {
      evictionListener.onEviction(key, existing, EvictionType.REPLACED);
    }
    return existing == null ? null : existing.value;
  }

  @Override
  @Nullable
  @CheckReturnValue
  public final V read(K key) {
    SizedValue entry = readInternal(key);
    if (entry != null) {
      return entry.value;
    }
    return null;
  }

  @Nullable
  @CheckReturnValue
  private SizedValue readInternal(K key) {
    checkNotNull(key, "key");
    SizedValue existing = delegate.get(key);
    if (existing != null && isExpired(key, existing.value, ticker.read())) {
      return null;
    }
    return existing;
  }

  @Override
  @Nullable
  public final V invalidate(K key) {
    return invalidate(key, EvictionType.EXPLICIT);
  }

  @Nullable
  private V invalidate(K key, EvictionType cause) {
    checkNotNull(key, "key");
    checkNotNull(cause, "cause");
    SizedValue existing = delegate.remove(key);
    if (existing != null) {
      evictionListener.onEviction(key, existing, cause);
    }
    return existing == null ? null : existing.value;
  }

  @Override
  public final void invalidateAll() {
    Iterator<Map.Entry<K, SizedValue>> iterator = delegate.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<K, SizedValue> entry = iterator.next();
      if (entry.getValue() != null) {
        evictionListener.onEviction(entry.getKey(), entry.getValue(), EvictionType.EXPLICIT);
      }
      iterator.remove();
    }
  }

  @Override
  @CheckReturnValue
  public final boolean hasCacheEntry(K key) {
    // call readInternal to filter already expired entry in the cache
    return readInternal(key) != null;
  }

  /** Returns shallow copied values in the cache. */
  public final List<V> values() {
    List<V> list = new ArrayList<>(delegate.size());
    for (SizedValue value : delegate.values()) {
      list.add(value.value);
    }
    return Collections.unmodifiableList(list);
  }

  /**
   * Cleans up cache if needed to fit into max size bytes by
   * removing expired entries and removing oldest entries by LRU order.
   * Returns TRUE if any unexpired entries were removed
   */
  protected final boolean fitToLimit() {
    boolean removedAnyUnexpired = false;
    if (estimatedSizeBytes <= estimatedMaxSizeBytes) {
      // new size is larger no need to do cleanup
      return false;
    }
    // cleanup expired entries
    long now = ticker.read();
    cleanupExpiredEntries(now);

    // cleanup eldest entry until new size limit
    Iterator<Map.Entry<K, SizedValue>> lruIter = delegate.entrySet().iterator();
    while (lruIter.hasNext() && estimatedMaxSizeBytes < this.estimatedSizeBytes) {
      Map.Entry<K, SizedValue> entry = lruIter.next();
      if (!shouldInvalidateEldestEntry(entry.getKey(), entry.getValue().value, now)) {
        break; // Violates some constraint like minimum age so stop our cleanup
      }
      lruIter.remove();
      // eviction listener will update the estimatedSizeBytes
      evictionListener.onEviction(entry.getKey(), entry.getValue(), EvictionType.SIZE);
      removedAnyUnexpired = true;
    }
    return removedAnyUnexpired;
  }

  /**
   * Resizes cache. If new size is smaller than current estimated size, it will free up space by
   * removing expired entries and removing oldest entries by LRU order.
   */
  public final void resize(long newSizeBytes) {
    this.estimatedMaxSizeBytes = newSizeBytes;
    fitToLimit();
  }

  @Override
  @CheckReturnValue
  public final int estimatedSize() {
    return delegate.size();
  }

  /** Returns {@code true} if any entries were removed. */
  public final boolean cleanupExpiredEntries() {
    return cleanupExpiredEntries(ticker.read());
  }

  private boolean cleanupExpiredEntries(long now) {
    return cleanupExpiredEntries(Integer.MAX_VALUE, now);
  }

  // maxExpiredEntries is by number of entries
  private boolean cleanupExpiredEntries(int maxExpiredEntries, long now) {
    checkArgument(maxExpiredEntries > 0, "maxExpiredEntries must be positive");
    boolean removedAny = false;
    Iterator<Map.Entry<K, SizedValue>> lruIter = delegate.entrySet().iterator();
    while (lruIter.hasNext() && maxExpiredEntries > 0) {
      Map.Entry<K, SizedValue> entry = lruIter.next();
      if (isExpired(entry.getKey(), entry.getValue().value, now)) {
        lruIter.remove();
        evictionListener.onEviction(entry.getKey(), entry.getValue(), EvictionType.EXPIRED);
        removedAny = true;
        maxExpiredEntries--;
      }
    }
    return removedAny;
  }

  @Override
  public final void close() {
    invalidateAll();
  }

  /** A {@link EvictionListener} keeps track of size. */
  private final class SizeHandlingEvictionListener implements EvictionListener<K, SizedValue> {

    private final EvictionListener<K, V> delegate;

    SizeHandlingEvictionListener(@Nullable EvictionListener<K, V> delegate) {
      this.delegate = delegate;
    }

    @Override
    public void onEviction(K key, SizedValue value, EvictionType cause) {
      estimatedSizeBytes -= value.size;
      if (delegate != null) {
        delegate.onEviction(key, value.value, cause);
      }
    }
  }

  private final class SizedValue {
    volatile int size;
    final V value;

    SizedValue(int size, V value) {
      this.size = size;
      this.value = value;
    }

    @Override
    public boolean equals(Object o) {
      // NOTE: the size doesn't affect equality
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LinkedHashLruCache<?, ?>.SizedValue that = (LinkedHashLruCache<?, ?>.SizedValue) o;
      return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      // NOTE: the size doesn't affect hashCode
      return Objects.hash(value);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("size", size)
          .add("value", value)
          .toString();
    }
  }
}
