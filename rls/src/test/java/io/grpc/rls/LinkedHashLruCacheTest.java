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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.google.common.base.Ticker;
import io.grpc.internal.FakeClock;
import io.grpc.rls.LruCache.EvictionListener;
import io.grpc.rls.LruCache.EvictionType;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class LinkedHashLruCacheTest {

  private static final int MAX_SIZE = 5;

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private final FakeClock fakeClock = new FakeClock();
  private final Ticker ticker = fakeClock.getTicker();

  @Mock
  private EvictionListener<Integer, Entry> evictionListener;
  private LinkedHashLruCache<Integer, Entry> cache;

  @Before
  public void setUp() {
    this.cache = new LinkedHashLruCache<Integer, Entry>(
        MAX_SIZE,
        evictionListener,
        fakeClock.getTicker()) {
      @Override
      protected boolean isExpired(Integer key, Entry value, long nowNanos) {
        return value.expireTime - nowNanos <= 0;
      }

      @Override
      protected int estimateSizeOf(Integer key, Entry value) {
        return value.size;
      }
    };
  }

  @Test
  public void eviction_size() {
    for (int i = 1; i <= MAX_SIZE; i++) {
      cache.cache(i, new Entry("Entry" + i, Long.MAX_VALUE));
    }
    cache.cache(MAX_SIZE + 1, new Entry("should kick the first", Long.MAX_VALUE));

    verify(evictionListener).onEviction(1, new Entry("Entry1", Long.MAX_VALUE), EvictionType.SIZE);
    assertThat(cache.estimatedSize()).isEqualTo(MAX_SIZE);
  }

  @Test
  public void size() {
    Entry entry1 = new Entry("Entry0", ticker.read() + 10);
    Entry entry2 = new Entry("Entry1", ticker.read() + 20);
    cache.cache(0, entry1);
    cache.cache(1, entry2);
    assertThat(cache.estimatedSize()).isEqualTo(2);

    assertThat(cache.invalidate(0)).isEqualTo(entry1);
    assertThat(cache.estimatedSize()).isEqualTo(1);

    assertThat(cache.invalidate(1)).isEqualTo(entry2);
    assertThat(cache.estimatedSize()).isEqualTo(0);
  }

  @Test
  public void eviction_expire() {
    Entry toBeEvicted = new Entry("Entry0", ticker.read() + 10);
    Entry survivor = new Entry("Entry1", ticker.read() + 20);
    cache.cache(0, toBeEvicted);
    cache.cache(1, survivor);

    fakeClock.forwardTime(10, TimeUnit.NANOSECONDS);
    cache.cleanupExpiredEntries();
    verify(evictionListener).onEviction(0, toBeEvicted, EvictionType.EXPIRED);

    fakeClock.forwardTime(10, TimeUnit.NANOSECONDS);
    cache.cleanupExpiredEntries();
    verify(evictionListener).onEviction(1, survivor, EvictionType.EXPIRED);
  }

  @Test
  public void eviction_explicit() {
    Entry toBeEvicted = new Entry("Entry0", ticker.read() + 10);
    Entry survivor = new Entry("Entry1", ticker.read() + 20);
    cache.cache(0, toBeEvicted);
    cache.cache(1, survivor);

    assertThat(cache.invalidate(0)).isEqualTo(toBeEvicted);

    verify(evictionListener).onEviction(0, toBeEvicted, EvictionType.EXPLICIT);
  }

  @Test
  public void eviction_replaced() {
    Entry toBeEvicted = new Entry("Entry0", ticker.read() + 10);
    Entry survivor = new Entry("Entry1", ticker.read() + 20);
    cache.cache(0, toBeEvicted);
    cache.cache(0, survivor);

    verify(evictionListener).onEviction(0, toBeEvicted, EvictionType.REPLACED);
  }

  @Test
  public void eviction_size_shouldEvictAlreadyExpired() {
    for (int i = 1; i <= MAX_SIZE; i++) {
      // last two entries are <= current time (already expired)
      cache.cache(i, new Entry("Entry" + i, ticker.read() + MAX_SIZE - i - 1));
    }
    cache.cache(MAX_SIZE + 1, new Entry("should kick the first", Long.MAX_VALUE));

    // should remove MAX_SIZE-1 instead of MAX_SIZE because MAX_SIZE is accessed later
    verify(evictionListener)
        .onEviction(eq(MAX_SIZE - 1), any(Entry.class), eq(EvictionType.EXPIRED));
    assertThat(cache.estimatedSize()).isEqualTo(MAX_SIZE);
  }

  @Test
  public void eviction_cleanupShouldRemoveAlreadyExpired() {
    for (int i = 1; i <= MAX_SIZE; i++) {
      // last entry is already expired when added
      cache.cache(i, new Entry("Entry" + i,
          ticker.read() + ((MAX_SIZE - i) * TimeUnit.MINUTES.toNanos(1)) + 1));
    }

    assertThat(cache.estimatedSize()).isEqualTo(MAX_SIZE);

    fakeClock.forwardTime(1, TimeUnit.MINUTES);
    cache.cleanupExpiredEntries();
    assertThat(cache.read(MAX_SIZE)).isNull();
    assertThat(cache.estimatedSize()).isEqualTo(MAX_SIZE - 1);
    verify(evictionListener).onEviction(eq(MAX_SIZE), any(Entry.class), eq(EvictionType.EXPIRED));
  }

  @Test
  public void updateEntrySize() {
    Entry entry = new Entry("Entry", ticker.read() + 10);

    cache.cache(1, entry);

    assertThat(cache.estimatedSizeBytes()).isEqualTo(1);
    entry.size = 10;
    assertThat(cache.estimatedSizeBytes()).isEqualTo(1);

    cache.updateEntrySize(1);

    assertThat(cache.estimatedSizeBytes()).isEqualTo(10);

    cache.updateEntrySize(1);

    assertThat(cache.estimatedSizeBytes()).isEqualTo(10);
  }

  @Test
  public void updateEntrySize_multipleEntries() {
    Entry entry1 = new Entry("Entry", ticker.read() + 10, 2);
    Entry entry2 = new Entry("Entry2", ticker.read() + 10, 3);

    cache.cache(1, entry1);
    cache.cache(2, entry2);

    assertThat(cache.estimatedSizeBytes()).isEqualTo(5);
    entry2.size = 1;
    assertThat(cache.estimatedSizeBytes()).isEqualTo(5);

    cache.updateEntrySize(2);

    assertThat(cache.estimatedSizeBytes()).isEqualTo(3);
  }

  @Test
  public void invalidateAll() {
    Entry entry1 = new Entry("Entry", ticker.read() + 10);
    Entry entry2 = new Entry("Entry2", ticker.read() + 10);

    cache.cache(1, entry1);
    cache.cache(2, entry2);

    assertThat(cache.estimatedSize()).isEqualTo(2);

    cache.invalidateAll();

    assertThat(cache.estimatedSize()).isEqualTo(0);
  }

  @Test
  public void resize() {
    Entry entry1 = new Entry("Entry", ticker.read() + 10);
    Entry entry2 = new Entry("Entry2", ticker.read() + 10);
    Entry entry3 = new Entry("Entry3", ticker.read() + 10);

    cache.cache(1, entry1);
    cache.cache(2, entry2);
    cache.cache(3, entry3);

    assertThat(cache.estimatedSize()).isEqualTo(3);

    cache.resize(2);

    assertThat(cache.estimatedSize()).isEqualTo(2);
    // eldest entry should be evicted
    assertThat(cache.hasCacheEntry(1)).isFalse();
  }

  private static final class Entry {
    String value;
    long expireTime;
    int size;

    Entry(String value, long expireTime) {
      this(value, expireTime, 1);
    }

    Entry(String value, long expireTime, int size) {
      this.value = value;
      this.expireTime = expireTime;
      this.size = size;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Entry entry = (Entry) o;
      return expireTime == entry.expireTime && Objects.equals(value, entry.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(value, expireTime);
    }
  }

  @Test
  public void testFitToLimitWithReSize() {

    Entry entry1 = new Entry("Entry1", ticker.read() + 10, 4);
    Entry entry2 = new Entry("Entry2", ticker.read() + 20, 1);
    Entry entry3 = new Entry("Entry3", ticker.read() + 30, 2);

    cache.cache(1, entry1);
    cache.cache(2, entry2);
    cache.cache(3, entry3);

    assertThat(cache.estimatedSize()).isEqualTo(2);
    assertThat(cache.estimatedSizeBytes()).isEqualTo(3);
    assertThat(cache.estimatedMaxSizeBytes()).isEqualTo(5);

    cache.resize(2);
    assertThat(cache.estimatedSize()).isEqualTo(1);
    assertThat(cache.estimatedSizeBytes()).isEqualTo(2);
    assertThat(cache.estimatedMaxSizeBytes()).isEqualTo(2);

    assertThat(cache.fitToLimit()).isEqualTo(false);
  }

  @Test
  public void testFitToLimit() {

    TestFitToLimitEviction localCache = new TestFitToLimitEviction(
            MAX_SIZE,
            evictionListener,
            fakeClock.getTicker()
    );

    Entry entry1 = new Entry("Entry1", ticker.read() + 10, 4);
    Entry entry2 = new Entry("Entry2", ticker.read() + 20, 2);
    Entry entry3 = new Entry("Entry3", ticker.read() + 30, 1);

    localCache.cache(1, entry1);
    localCache.cache(2, entry2);
    localCache.cache(3, entry3);

    assertThat(localCache.estimatedSize()).isEqualTo(3);
    assertThat(localCache.estimatedSizeBytes()).isEqualTo(7);
    assertThat(localCache.estimatedMaxSizeBytes()).isEqualTo(5);

    localCache.enableEviction();

    assertThat(localCache.fitToLimit()).isEqualTo(true);

    assertThat(localCache.values().contains(entry1)).isFalse();
    assertThat(localCache.values().containsAll(Arrays.asList(entry2, entry3))).isTrue();

    assertThat(localCache.estimatedSize()).isEqualTo(2);
    assertThat(localCache.estimatedSizeBytes()).isEqualTo(3);
    assertThat(localCache.estimatedMaxSizeBytes()).isEqualTo(5);
  }

  private static class TestFitToLimitEviction extends LinkedHashLruCache<Integer, Entry> {

    private boolean allowEviction = false;

    TestFitToLimitEviction(
            long estimatedMaxSizeBytes,
            @Nullable EvictionListener<Integer, Entry> evictionListener,
            Ticker ticker) {
      super(estimatedMaxSizeBytes, evictionListener, ticker);
    }

    @Override
    protected boolean isExpired(Integer key, Entry value, long nowNanos) {
      return value.expireTime - nowNanos <= 0;
    }

    @Override
    protected int estimateSizeOf(Integer key, Entry value) {
      return value.size;
    }

    @Override
    protected boolean shouldInvalidateEldestEntry(Integer eldestKey, Entry eldestValue, long now) {
      return allowEviction && super.shouldInvalidateEldestEntry(eldestKey, eldestValue, now);
    }

    public void enableEviction() {
      allowEviction = true;
    }
  }
}
