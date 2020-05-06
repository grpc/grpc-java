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
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import io.grpc.rls.DoNotUseDirectScheduledExecutorService.FakeTimeProvider;
import io.grpc.rls.LruCache.EvictionListener;
import io.grpc.rls.LruCache.EvictionType;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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

  private final DoNotUseDirectScheduledExecutorService fakeScheduledService =
      mock(DoNotUseDirectScheduledExecutorService.class, CALLS_REAL_METHODS);
  private final FakeTimeProvider timeProvider = fakeScheduledService.getFakeTimeProvider();

  @Mock
  private EvictionListener<Integer, Entry> evictionListener;
  private LinkedHashLruCache<Integer, Entry> cache;

  @Before
  public void setUp() {
    this.cache = new LinkedHashLruCache<Integer, Entry>(
        MAX_SIZE,
        evictionListener,
        10,
        TimeUnit.NANOSECONDS,
        fakeScheduledService,
        timeProvider) {
      @Override
      protected boolean isExpired(Integer key, Entry value, long nowNanos) {
        return value.expireTime <= nowNanos;
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
    Entry entry1 = new Entry("Entry0", timeProvider.currentTimeNanos() + 10);
    Entry entry2 = new Entry("Entry1", timeProvider.currentTimeNanos() + 20);
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
    Entry toBeEvicted = new Entry("Entry0", timeProvider.currentTimeNanos() + 10);
    Entry survivor = new Entry("Entry1", timeProvider.currentTimeNanos() + 20);
    cache.cache(0, toBeEvicted);
    cache.cache(1, survivor);

    timeProvider.forwardTime(10, TimeUnit.NANOSECONDS);
    verify(evictionListener).onEviction(0, toBeEvicted, EvictionType.EXPIRED);

    timeProvider.forwardTime(10, TimeUnit.NANOSECONDS);
    verify(evictionListener).onEviction(1, survivor, EvictionType.EXPIRED);
  }

  @Test
  public void eviction_explicit() {
    Entry toBeEvicted = new Entry("Entry0", timeProvider.currentTimeNanos() + 10);
    Entry survivor = new Entry("Entry1", timeProvider.currentTimeNanos() + 20);
    cache.cache(0, toBeEvicted);
    cache.cache(1, survivor);

    assertThat(cache.invalidate(0)).isEqualTo(toBeEvicted);

    verify(evictionListener).onEviction(0, toBeEvicted, EvictionType.EXPLICIT);
  }

  @Test
  public void eviction_replaced() {
    Entry toBeEvicted = new Entry("Entry0", timeProvider.currentTimeNanos() + 10);
    Entry survivor = new Entry("Entry1", timeProvider.currentTimeNanos() + 20);
    cache.cache(0, toBeEvicted);
    cache.cache(0, survivor);

    verify(evictionListener).onEviction(0, toBeEvicted, EvictionType.REPLACED);
  }

  @Test
  public void eviction_size_shouldEvictAlreadyExpired() {
    for (int i = 1; i <= MAX_SIZE; i++) {
      // last two entries are <= current time (already expired)
      cache.cache(i, new Entry("Entry" + i, timeProvider.currentTimeNanos() + MAX_SIZE - i - 1));
    }
    cache.cache(MAX_SIZE + 1, new Entry("should kick the first", Long.MAX_VALUE));

    // should remove MAX_SIZE-1 instead of MAX_SIZE because MAX_SIZE is accessed later
    verify(evictionListener)
        .onEviction(eq(MAX_SIZE - 1), any(Entry.class), eq(EvictionType.EXPIRED));
    assertThat(cache.estimatedSize()).isEqualTo(MAX_SIZE);
  }

  @Test
  public void eviction_get_shouldNotReturnAlreadyExpired() {
    for (int i = 1; i <= MAX_SIZE; i++) {
      // last entry is already expired when added
      cache.cache(i, new Entry("Entry" + i, timeProvider.currentTimeNanos() + MAX_SIZE - i));
    }

    assertThat(cache.estimatedSize()).isEqualTo(MAX_SIZE);
    assertThat(cache.read(MAX_SIZE)).isNull();
    assertThat(cache.estimatedSize()).isEqualTo(MAX_SIZE - 1);
    verify(evictionListener).onEviction(eq(MAX_SIZE), any(Entry.class), eq(EvictionType.EXPIRED));
  }

  @Test
  public void updateEntrySize() {
    Entry entry = new Entry("Entry", timeProvider.currentTimeNanos() + 10);

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
    Entry entry1 = new Entry("Entry", timeProvider.currentTimeNanos() + 10, 2);
    Entry entry2 = new Entry("Entry2", timeProvider.currentTimeNanos() + 10, 3);

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
    Entry entry1 = new Entry("Entry", timeProvider.currentTimeNanos() + 10);
    Entry entry2 = new Entry("Entry2", timeProvider.currentTimeNanos() + 10);

    cache.cache(1, entry1);
    cache.cache(2, entry2);

    assertThat(cache.estimatedSize()).isEqualTo(2);

    cache.invalidateAll(ImmutableList.of(1, 2));

    assertThat(cache.estimatedSize()).isEqualTo(0);
  }

  @Test
  public void resize() {
    Entry entry1 = new Entry("Entry", timeProvider.currentTimeNanos() + 10);
    Entry entry2 = new Entry("Entry2", timeProvider.currentTimeNanos() + 10);
    Entry entry3 = new Entry("Entry3", timeProvider.currentTimeNanos() + 10);

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
}
