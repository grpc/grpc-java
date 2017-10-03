/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link Contexts}.
 */
@RunWith(JUnit4.class)
public class WithCancellableContextTest {
  private static Context.Key<Object> contextKey = Context.key("key");
  private static Object contextValue = new Object();
  private Context.CancellableContext cancellableContext;

  @Before
  public void setUp() {
    cancellableContext = Context.ROOT.withValue(contextKey, contextValue).withCancellation();
  }

  @Test
  public void withContext_close() throws Exception {
    assertSame(Context.ROOT, Context.current());
    // Can not try-with-resources at language level 1.6
    WithCancellableContext wc = WithCancellableContext.enter(cancellableContext);
    try {
      verifyInCancellableContext();
    } finally {
      wc.close();
    }
    assertSame(Context.ROOT, Context.current());
    assertTrue(cancellableContext.isCancelled());
  }

  @Test
  public void cancellableCtxApplier_runnable_enters() throws Exception {
    assertSame(Context.ROOT, Context.current());
    WithCancellableContext.with(cancellableContext).run(new Runnable() {
      @Override
      public void run() {
        verifyInCancellableContext();
      }
    });
    assertSame(Context.ROOT, Context.current());
  }

  @Test
  public void cancellableCtxApplier_callable_enters() throws Exception {
    assertSame(Context.ROOT, Context.current());
    WithCancellableContext.with(cancellableContext).call(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        verifyInCancellableContext();
        return null;
      }
    });
    assertSame(Context.ROOT, Context.current());
  }

  @Test
  public void cancellableCtxApplier_asyncCallable_enters() {
    assertSame(Context.ROOT, Context.current());
    @SuppressWarnings("unused")
    ListenableFuture<Integer> ignored = WithCancellableContext.with(cancellableContext)
        .callAsync(new Callable<ListenableFuture<Integer>>() {
          @Override
          public ListenableFuture<Integer> call() throws Exception {
            verifyInCancellableContext();
            return Futures.immediateFuture(1);
          }
        });
    assertSame(Context.ROOT, Context.current());
  }

  @Test
  public void cancellableCtxApplier_runnable() throws Exception {
    WithCancellableContext.with(cancellableContext).run(new Runnable() {
      @Override
      public void run() {
      }
    });
    assertTrue(cancellableContext.isCancelled());
  }

  @Test
  public void cancellableCtxApplier_callable() throws Exception {
    WithCancellableContext.with(cancellableContext).call(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return null;
      }
    });
    assertTrue(cancellableContext.isCancelled());
  }

  @Test
  public void cancellableCtxApplier_asyncCallable_already_success() {
    @SuppressWarnings("unused")
    ListenableFuture<Integer> ignored = WithCancellableContext.with(cancellableContext)
        .callAsync(new Callable<ListenableFuture<Integer>>() {
          @Override
          public ListenableFuture<Integer> call() throws Exception {
            return Futures.immediateFuture(1);
          }
        });
    assertTrue(cancellableContext.isCancelled());
  }

  @Test
  public void cancellableCtxApplier_asyncCallable_already_fail() throws Exception {
    final FakeException fakeException = new FakeException();
    ListenableFuture<Integer> future = WithCancellableContext.with(cancellableContext)
        .callAsync(new Callable<ListenableFuture<Integer>>() {
          @Override
          public ListenableFuture<Integer> call() throws Exception {
            return Futures.immediateFailedFuture(fakeException);
          }
        });
    assertTrue(cancellableContext.isCancelled());
    boolean exceptionCaught = false;
    try {
      future.get();
    } catch (ExecutionException e) {
      if (e.getCause() == fakeException) {
        exceptionCaught = true;
      }
    }
    assertTrue(exceptionCaught);
  }

  @Test
  public void applier_asyncCallable_success() {
    final SettableFuture<Integer> settable = SettableFuture.create();
    ListenableFuture<Integer> ret = WithCancellableContext.with(cancellableContext)
        .callAsync(new Callable<ListenableFuture<Integer>>() {
          @Override
          public ListenableFuture<Integer> call() throws Exception {
            return settable;
          }
        });
    assertFalse(ret.isDone());
    assertFalse(cancellableContext.isCancelled());
    settable.set(1);
    assertTrue(cancellableContext.isCancelled());
  }

  @Test
  public void applier_asyncCallable_fail() {
    final SettableFuture<Integer> settable = SettableFuture.create();
    @SuppressWarnings("unused")
    ListenableFuture<Integer> ignored = WithCancellableContext.with(cancellableContext)
        .callAsync(new Callable<ListenableFuture<Integer>>() {
          @Override
          public ListenableFuture<Integer> call() throws Exception {
            return settable;
          }
        });
    assertFalse(cancellableContext.isCancelled());
    settable.setException(new FakeException());
    assertTrue(cancellableContext.isCancelled());
  }

  @Test
  public void applier_asyncCallable_userCancel() {
    ListenableFuture<Integer> ret = WithCancellableContext.with(cancellableContext)
        .callAsync(new Callable<ListenableFuture<Integer>>() {
          @Override
          public ListenableFuture<Integer> call() throws Exception {
            return SettableFuture.create();
          }
        });
    assertFalse(ret.isCancelled());
    assertFalse(cancellableContext.isCancelled());
    ret.cancel(true);
    assertTrue(cancellableContext.isCancelled());
  }

  @Test
  public void applier_asyncCallable_alreadyCancelledWhenListenersNotified() {
    ListenableFuture<Integer> ret = WithCancellableContext.with(cancellableContext)
        .callAsync(new Callable<ListenableFuture<Integer>>() {
          @Override
          public ListenableFuture<Integer> call() throws Exception {
            return SettableFuture.create();
          }
        });
    assertFalse(ret.isCancelled());
    assertFalse(cancellableContext.isCancelled());

    final AtomicBoolean listenerFired = new AtomicBoolean();
    ret.addListener(new Runnable() {
      @Override
      public void run() {
        assertTrue(cancellableContext.isCancelled());
        listenerFired.set(true);
      }
    }, MoreExecutors.directExecutor());
    assertFalse(listenerFired.get());
    ret.cancel(true);
    assertTrue(listenerFired.get());
  }

  @Test(expected = FakeException.class)
  public void applier_runnable_throws() {
    try {
      WithCancellableContext.with(cancellableContext).run(
          new Runnable() {
            @Override
            public void run() {
              throw new FakeException();
            }
          });
      // should not be reached
      Assert.fail();
    } finally {
      assertTrue(cancellableContext.isCancelled());
    }
  }

  @Test(expected = FakeException.class)
  public void applier_callable_throws() throws Exception {
    try {
      WithCancellableContext.with(cancellableContext).call(
          new Callable<Void>() {
            @Override
            public Void call() throws Exception {
              throw new FakeException();
            }
          }
      );
      // should not be reached
      Assert.fail();
    } finally {
      assertTrue(cancellableContext.isCancelled());
    }
  }

  @Test
  public void applier_asyncCallable_throws() throws Exception {
    final FakeException fakeException = new FakeException();
    ListenableFuture<Integer> future = WithCancellableContext.with(cancellableContext)
        .callAsync(new Callable<ListenableFuture<Integer>>() {
          @Override
          public ListenableFuture<Integer> call() throws Exception {
            throw fakeException;
          }
        });
    assertTrue(cancellableContext.isCancelled());
    boolean exceptionCaught = false;
    try {
      future.get();
      // should not be reached
      Assert.fail();
    } catch (ExecutionException expected) {
      if (expected.getCause() == fakeException) {
        exceptionCaught = true;
      }
    }
    assertTrue(exceptionCaught);
  }

  private void verifyInCancellableContext() {
    // Context.current() always returns an uncancellable surrogate, so it doesn't make sense to
    // check that the current context is a CancellableContext. So let's just make sure it looks
    // equivalent to the expected context.
    assertSame(contextValue, contextKey.get());
    assertFalse(cancellableContext.isCancelled());
    assertFalse(Context.current().isCancelled());
  }

  /**
   * A class that lets us have a bit of type safety when testing thrown exceptions.
   */
  private static class FakeException extends RuntimeException { }
}
