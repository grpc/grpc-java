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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.ManagedChannel;
import io.grpc.xds.SharedResourceManager.ManagedChannelResource;
import io.grpc.xds.SharedResourceManager.ResourceCloseable;
import io.grpc.xds.SharedResourceManager.SharedResource;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link SharedResourceManager}. */
@RunWith(JUnit4.class)
public class SharedResourceManagerTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private ResourceCloseable mockResourceA;
  @Mock private ResourceCloseable mockResourceB;
  @Mock private ResourceCloseable mockResource;
  @Mock private ManagedChannel mockChannel;

  private SharedResourceManager<String, ResourceCloseable> manager;
  private final AtomicInteger createCount = new AtomicInteger();

  @Before
  public void setUp() {
    manager = new SharedResourceManager<>(key -> {
      createCount.incrementAndGet();
      if ("keyA".equals(key)) {
        return mockResourceA;
      } else if ("keyB".equals(key)) {
        return mockResourceB;
      }
      throw new IllegalArgumentException("Unexpected key: " + key);
    });
  }

  // ---- SharedResourceManager tests ----

  // RefCount model:
  //   creation:  refCount = 1  (manager's creation ref)
  //   acquire(): refCount += 1 (retain)
  //   release(): refCount -= 1 (release)
  //   close():   refCount -= 1 (releases creation ref)
  //
  // So after one acquire(), refCount = 2 (creation + acquire).
  // Resource closes when refCount reaches 0.

  @Test
  public void acquire_createsNewResourceOnFirstCall() {
    ResourceCloseable resource = manager.acquire("keyA");
    assertThat(resource).isSameInstanceAs(mockResourceA);
    assertThat(createCount.get()).isEqualTo(1);
  }

  @Test
  public void acquire_returnsSameResourceOnSubsequentCalls() {
    ResourceCloseable first = manager.acquire("keyA");
    ResourceCloseable second = manager.acquire("keyA");
    assertThat(second).isSameInstanceAs(first);
    assertThat(createCount.get()).isEqualTo(1);
  }

  @Test
  public void acquire_differentKeysCreateIndependentResources() {
    ResourceCloseable resourceA = manager.acquire("keyA");
    ResourceCloseable resourceB = manager.acquire("keyB");
    assertThat(resourceA).isSameInstanceAs(mockResourceA);
    assertThat(resourceB).isSameInstanceAs(mockResourceB);
    assertThat(createCount.get()).isEqualTo(2);
  }

  @Test
  public void acquire_incrementsRefCount() {
    // Verifies that acquire() calls retain(), not just returns the cached value.
    // After acquire: refCount = 2 (creation=1 + retain=+1).
    // Release once: refCount = 1 — still alive.
    manager.acquire("keyA");
    boolean closed = manager.release("keyA"); // 2 -> 1
    assertThat(closed).isFalse();
    verify(mockResourceA, never()).close();
  }

  @Test
  public void release_decrementsRefCount() {
    manager.acquire("keyA");
    manager.acquire("keyA"); // refCount = 3 (creation + 2 retains)
    boolean closed = manager.release("keyA"); // refCount = 2
    assertThat(closed).isFalse();
    verify(mockResourceA, never()).close();
  }

  @Test
  public void release_doesNotCloseUntilAllRefsReleased() {
    // acquire() adds +1 on top of creation ref.
    // Must release the acquire ref AND the creation ref (via close()) to close resource.
    manager.acquire("keyA"); // refCount = 2
    manager.release("keyA"); // refCount = 1 (creation ref remains)
    verify(mockResourceA, never()).close();

    // Only after close() releases the creation ref does it close.
    manager.close(); // refCount = 0
    verify(mockResourceA).close();
  }

  @Test
  public void release_removesEntryWhenAllRefsGone() {
    // acquire + release leaves creation ref. close() drops creation ref.
    manager.acquire("keyA"); // refCount = 2
    manager.release("keyA"); // refCount = 1
    manager.close(); // refCount = 0, closes
    verify(mockResourceA).close();
    // Verify entry was evicted.
    assertThat(manager.release("keyA")).isFalse();
  }

  @Test
  public void release_evictsAndRethrowsOnCloseException() {
    RuntimeException boom = new RuntimeException("boom");
    doThrow(boom).when(mockResourceA).close();
    manager.acquire("keyA"); // refCount = 2
    manager.release("keyA"); // refCount = 1
    // Releasing the creation ref triggers close(), which throws.
    RuntimeException thrown = assertThrows(
        RuntimeException.class,
        () -> manager.release("keyA")); // refCount = 0 -> close() -> throws
    assertThat(thrown).isSameInstanceAs(boom);
    // Entry should be evicted even on exception; re-acquire creates new.
    createCount.set(0);
    manager.acquire("keyA");
    assertThat(createCount.get()).isEqualTo(1);
  }

  @Test
  public void release_returnsFalseWhenKeyDoesNotExist() {
    boolean closed = manager.release("nonExistentKey");
    assertThat(closed).isFalse();
  }

  @Test
  public void close_releasesCreationRefForAllResources() {
    // acquire each key: refCount = 2 each (creation + retain)
    manager.acquire("keyA");
    manager.acquire("keyB");
    // Release the acquire refs.
    manager.release("keyA"); // refCount = 1
    manager.release("keyB"); // refCount = 1
    // close() releases the creation ref -> refCount = 0 -> closes.
    manager.close();
    verify(mockResourceA).close();
    verify(mockResourceB).close();
  }

  @Test
  public void close_withoutRelease_doesNotCloseIfAcquireRefsHeld() {
    // This tests that close() only releases the creation ref, not the acquire ref.
    // If in-flight RPCs still hold references, the resource stays alive.
    manager.acquire("keyA"); // refCount = 2
    manager.close(); // refCount = 1 (only creation ref released)
    verify(mockResourceA, never()).close(); // Still alive! Acquire ref held.
  }

  @Test
  public void independentKeyLifecycle() {
    manager.acquire("keyA");
    manager.acquire("keyB");
    // Release both refs for keyA: acquire ref + creation ref.
    manager.release("keyA"); // refCount = 1
    manager.release("keyA"); // refCount = 0, closes
    verify(mockResourceA).close();
    verify(mockResourceB, never()).close();
    // keyB should still be accessible.
    ResourceCloseable stillB = manager.acquire("keyB");
    assertThat(stillB).isSameInstanceAs(mockResourceB);
  }

  @Test
  public void acquire_afterFullRelease_createsNewResource() {
    // Acquire, then fully release both refs.
    manager.acquire("keyA"); // refCount = 2
    manager.release("keyA"); // refCount = 1
    manager.release("keyA"); // refCount = 0, evicted
    verify(mockResourceA).close();

    // Re-acquire should create a new resource via the factory.
    createCount.set(0);
    ResourceCloseable reacquired = manager.acquire("keyA");
    assertThat(createCount.get()).isEqualTo(1);
    assertThat(reacquired).isSameInstanceAs(mockResourceA);
  }

  @Test
  public void close_onEmptyManager_doesNotThrow() {
    manager.close();
    verify(mockResourceA, never()).close();
    verify(mockResourceB, never()).close();
  }

  @Test
  public void close_afterPartialRelease_closesRemaining() {
    manager.acquire("keyA");
    manager.acquire("keyA"); // refCount = 3 (creation + 2 retains)
    manager.acquire("keyB"); // refCount = 2 (creation + 1 retain)

    // Partially release keyA (3 -> 2).
    manager.release("keyA");
    verify(mockResourceA, never()).close();

    // Release keyB's acquire ref.
    manager.release("keyB"); // refCount = 1 (creation ref only)
    verify(mockResourceB, never()).close();

    // close() releases creation refs: keyA 2->1, keyB 1->0.
    manager.close();
    verify(mockResourceA, never()).close(); // keyA still has 1 acquire ref held
    verify(mockResourceB).close(); // keyB fully released
  }

  @Test
  public void release_multipleReleasesOnSameKey() {
    manager.acquire("keyA"); // refCount = 2
    manager.acquire("keyA"); // refCount = 3
    manager.acquire("keyA"); // refCount = 4

    manager.release("keyA"); // 4 -> 3
    verify(mockResourceA, never()).close();

    manager.release("keyA"); // 3 -> 2
    verify(mockResourceA, never()).close();

    manager.release("keyA"); // 2 -> 1
    verify(mockResourceA, never()).close();

    manager.release("keyA"); // 1 -> 0, close and evict
    verify(mockResourceA).close();
  }

  @Test
  public void acquire_retriesWhenRetainFailsOnStaleEntry() {
    AtomicInteger callCount = new AtomicInteger();
    ResourceCloseable firstResource = new ResourceCloseable() {
      @Override
      public void close() {}
    };
    ResourceCloseable secondResource = new ResourceCloseable() {
      @Override
      public void close() {}
    };
    SharedResourceManager<String, ResourceCloseable> testManager =
        new SharedResourceManager<>(key -> {
          int c = callCount.incrementAndGet();
          return c == 1 ? firstResource : secondResource;
        });

    // First acquire creates and caches.
    ResourceCloseable got = testManager.acquire("keyA");
    assertThat(got).isSameInstanceAs(firstResource);

    // Fully release: acquire ref + creation ref.
    testManager.release("keyA"); // refCount 2 -> 1
    testManager.release("keyA"); // refCount 1 -> 0, closes

    // Acquire again — should create new resource.
    ResourceCloseable got2 = testManager.acquire("keyA");
    assertThat(got2).isSameInstanceAs(secondResource);
    assertThat(callCount.get()).isEqualTo(2);
  }

  @Test
  public void acquire_throwsIllegalStateExceptionAfterClose() {
    manager.close();
    assertThrows(IllegalStateException.class, () -> manager.acquire("keyA"));
  }

  // The putIfAbsent race test was removed because computeIfAbsent guarantees
  // the factory runs exactly once per absent key, eliminating the race entirely.

  // ---- Tests for SharedResource (nested class) ----

  @Test
  public void sharedResource_initialRefCount_isOne() {
    SharedResource<ResourceCloseable> shared = new SharedResource<>(mockResource);
    assertThat(shared.getRefCount()).isEqualTo(1);
  }

  @Test
  public void sharedResource_get_returnsWrappedResource() {
    SharedResource<ResourceCloseable> shared = new SharedResource<>(mockResource);
    assertThat(shared.get()).isSameInstanceAs(mockResource);
  }

  @Test
  public void sharedResource_retain_incrementsRefCount() {
    SharedResource<ResourceCloseable> shared = new SharedResource<>(mockResource);
    boolean retained = shared.retain();
    assertThat(retained).isTrue();
    assertThat(shared.getRefCount()).isEqualTo(2);
  }

  @Test
  public void sharedResource_retain_multipleIncrements() {
    SharedResource<ResourceCloseable> shared = new SharedResource<>(mockResource);
    shared.retain();
    shared.retain();
    assertThat(shared.getRefCount()).isEqualTo(3);
  }

  @Test
  public void sharedResource_retain_returnsFalseWhenDead() {
    SharedResource<ResourceCloseable> shared = new SharedResource<>(mockResource);
    shared.release();
    boolean retained = shared.retain();
    assertThat(retained).isFalse();
    assertThat(shared.getRefCount()).isEqualTo(0);
  }

  @Test
  public void sharedResource_release_decrementsRefCount() {
    SharedResource<ResourceCloseable> shared = new SharedResource<>(mockResource);
    shared.retain(); // refCount = 2
    boolean closed = shared.release(); // refCount = 1
    assertThat(closed).isFalse();
    assertThat(shared.getRefCount()).isEqualTo(1);
    verify(mockResource, never()).close();
  }

  @Test
  public void sharedResource_release_closesResourceWhenRefCountReachesZero() {
    SharedResource<ResourceCloseable> shared = new SharedResource<>(mockResource);
    boolean closed = shared.release();
    assertThat(closed).isTrue();
    assertThat(shared.getRefCount()).isEqualTo(0);
    verify(mockResource).close();
  }

  @Test
  public void sharedResource_release_throwsAssertionErrorOnUnderflow() {
    SharedResource<ResourceCloseable> shared = new SharedResource<>(mockResource);
    shared.release(); // refCount = 0, closes resource
    verify(mockResource).close();

    assertThrows(
        AssertionError.class,
        () -> shared.release());

    // Verify close was only called once (the first time)
    verify(mockResource, times(1)).close();
  }

  // ---- Tests for ManagedChannelResource (nested class) ----

  @Test
  public void channelResource_constructor_rejectsNullChannel() {
    assertThrows(NullPointerException.class, () -> new ManagedChannelResource(null));
  }

  @Test
  public void channelResource_getChannel_returnsWrappedChannel() {
    ManagedChannelResource resource = new ManagedChannelResource(mockChannel);
    assertThat(resource.getChannel()).isSameInstanceAs(mockChannel);
  }

  @Test
  public void channelResource_close_callsChannelShutdown() {
    ManagedChannelResource resource = new ManagedChannelResource(mockChannel);
    resource.close();
    verify(mockChannel).shutdown();
  }

  @Test
  public void channelResource_close_doesNotCallShutdownNow() {
    ManagedChannelResource resource = new ManagedChannelResource(mockChannel);
    resource.close();
    verify(mockChannel, never()).shutdownNow();
  }

  @Test
  public void channelResource_implementsResourceCloseable() {
    ManagedChannelResource resource = new ManagedChannelResource(mockChannel);
    assertThat(resource).isInstanceOf(ResourceCloseable.class);
    assertThat(resource).isInstanceOf(AutoCloseable.class);
  }
}
