/*
 * Copyright 2016, gRPC Authors All rights reserved.
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

import static io.grpc.Contexts.interceptCall;
import static io.grpc.Contexts.statusFromCancelled;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.internal.FakeClock;
import io.grpc.internal.NoopServerCall;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link Contexts}.
 */
@RunWith(JUnit4.class)
public class ContextsTest {
  private static Context.Key<Object> contextKey = Context.key("key");
  /** For use in comparing context by reference. */
  private Object contextValue = new Object();
  private Context uniqueContext = Context.ROOT.withValue(contextKey, contextValue);
  @SuppressWarnings("unchecked")
  private ServerCall<Object, Object> call = new NoopServerCall<Object, Object>();
  private Metadata headers = new Metadata();

  @Test
  public void interceptCall_basic() {
    Context origContext = Context.current();
    final Object message = new Object();
    final List<Integer> methodCalls = new ArrayList<Integer>();
    final ServerCall.Listener<Object> listener = new ServerCall.Listener<Object>() {
      @Override public void onMessage(Object messageIn) {
        assertSame(message, messageIn);
        assertSame(uniqueContext, Context.current());
        methodCalls.add(1);
      }

      @Override public void onHalfClose() {
        assertSame(uniqueContext, Context.current());
        methodCalls.add(2);
      }

      @Override public void onCancel() {
        assertSame(uniqueContext, Context.current());
        methodCalls.add(3);
      }

      @Override public void onComplete() {
        assertSame(uniqueContext, Context.current());
        methodCalls.add(4);
      }

      @Override public void onReady() {
        assertSame(uniqueContext, Context.current());
        methodCalls.add(5);
      }
    };
    ServerCall.Listener<Object> wrapped = interceptCall(uniqueContext, call, headers,
        new ServerCallHandler<Object, Object>() {
          @Override
          public ServerCall.Listener<Object> startCall(
              ServerCall<Object, Object> call, Metadata headers) {
            assertSame(ContextsTest.this.call, call);
            assertSame(ContextsTest.this.headers, headers);
            assertSame(uniqueContext, Context.current());
            return listener;
          }
        });
    assertSame(origContext, Context.current());

    wrapped.onMessage(message);
    wrapped.onHalfClose();
    wrapped.onCancel();
    wrapped.onComplete();
    wrapped.onReady();
    assertEquals(Arrays.asList(1, 2, 3, 4, 5), methodCalls);
    assertSame(origContext, Context.current());
  }

  @Test
  public void interceptCall_restoresIfNextThrows() {
    Context origContext = Context.current();
    try {
      interceptCall(uniqueContext, call, headers, new ServerCallHandler<Object, Object>() {
        @Override
        public ServerCall.Listener<Object> startCall(
            ServerCall<Object, Object> call, Metadata headers) {
          throw new RuntimeException();
        }
      });
      fail("Expected exception");
    } catch (RuntimeException expected) {
    }
    assertSame(origContext, Context.current());
  }

  @Test
  public void interceptCall_restoresIfListenerThrows() {
    Context origContext = Context.current();
    final ServerCall.Listener<Object> listener = new ServerCall.Listener<Object>() {
      @Override public void onMessage(Object messageIn) {
        throw new RuntimeException();
      }

      @Override public void onHalfClose() {
        throw new RuntimeException();
      }

      @Override public void onCancel() {
        throw new RuntimeException();
      }

      @Override public void onComplete() {
        throw new RuntimeException();
      }

      @Override public void onReady() {
        throw new RuntimeException();
      }
    };
    ServerCall.Listener<Object> wrapped = interceptCall(uniqueContext, call, headers,
        new ServerCallHandler<Object, Object>() {
          @Override
          public ServerCall.Listener<Object> startCall(
              ServerCall<Object, Object> call, Metadata headers) {
            return listener;
          }
        });

    try {
      wrapped.onMessage(new Object());
      fail("Exception expected");
    } catch (RuntimeException expected) {
    }
    try {
      wrapped.onHalfClose();
      fail("Exception expected");
    } catch (RuntimeException expected) {
    }
    try {
      wrapped.onCancel();
      fail("Exception expected");
    } catch (RuntimeException expected) {
    }
    try {
      wrapped.onComplete();
      fail("Exception expected");
    } catch (RuntimeException expected) {
    }
    try {
      wrapped.onReady();
      fail("Exception expected");
    } catch (RuntimeException expected) {
    }
    assertSame(origContext, Context.current());
  }

  @Test
  public void statusFromCancelled_returnNullIfCtxNotCancelled() {
    Context context = Context.current();
    assertFalse(context.isCancelled());
    assertNull(statusFromCancelled(context));
  }

  @Test
  public void statusFromCancelled_returnStatusAsSetOnCtx() {
    Context.CancellableContext cancellableContext = Context.current().withCancellation();
    cancellableContext.cancel(Status.DEADLINE_EXCEEDED.withDescription("foo bar").asException());
    Status status = statusFromCancelled(cancellableContext);
    assertNotNull(status);
    assertEquals(Status.Code.DEADLINE_EXCEEDED, status.getCode());
    assertEquals("foo bar", status.getDescription());
  }

  @Test
  public void statusFromCancelled_shouldReturnStatusWithCauseAttached() {
    Context.CancellableContext cancellableContext = Context.current().withCancellation();
    Throwable t = new Throwable();
    cancellableContext.cancel(t);
    Status status = statusFromCancelled(cancellableContext);
    assertNotNull(status);
    assertEquals(Status.Code.CANCELLED, status.getCode());
    assertSame(t, status.getCause());
  }

  @Test
  public void statusFromCancelled_TimeoutExceptionShouldMapToDeadlineExceeded() {
    FakeClock fakeClock = new FakeClock();
    Context.CancellableContext cancellableContext = Context.current()
        .withDeadlineAfter(100, TimeUnit.NANOSECONDS, fakeClock.getScheduledExecutorService());
    fakeClock.forwardTime(System.nanoTime(), TimeUnit.NANOSECONDS);
    fakeClock.forwardNanos(100);

    assertTrue(cancellableContext.isCancelled());
    assertThat(cancellableContext.cancellationCause(), instanceOf(TimeoutException.class));

    Status status = statusFromCancelled(cancellableContext);
    assertNotNull(status);
    assertEquals(Status.Code.DEADLINE_EXCEEDED, status.getCode());
    assertEquals("context timed out", status.getDescription());
  }

  @Test
  public void statusFromCancelled_returnCancelledIfCauseIsNull() {
    Context.CancellableContext cancellableContext = Context.current().withCancellation();
    cancellableContext.cancel(null);
    assertTrue(cancellableContext.isCancelled());
    Status status = statusFromCancelled(cancellableContext);
    assertNotNull(status);
    assertEquals(Status.Code.CANCELLED, status.getCode());
  }

  /** This is a whitebox test, to verify a special case of the implementation. */
  @Test
  public void statusFromCancelled_StatusUnknownShouldWork() {
    Context.CancellableContext cancellableContext = Context.current().withCancellation();
    Exception e = Status.UNKNOWN.asException();
    cancellableContext.cancel(e);
    assertTrue(cancellableContext.isCancelled());

    Status status = statusFromCancelled(cancellableContext);
    assertNotNull(status);
    assertEquals(Status.Code.UNKNOWN, status.getCode());
    assertSame(e, status.getCause());
  }

  @Test
  public void statusFromCancelled_shouldThrowIfCtxIsNull() {
    try {
      statusFromCancelled(null);
      fail("NPE expected");
    } catch (NullPointerException npe) {
      assertEquals("context must not be null", npe.getMessage());
    }
  }

  @Test
  public void cancellableCtxApplier_runnable_enters() throws Exception {
    Context.CancellableContext cancellableContext = makeCancellableContext();
    assertSame(Context.ROOT, Context.current());
    Contexts.cancellableContextApplier(cancellableContext).run(new Runnable() {
      @Override
      public void run() {
        verifyInCancellableContext();
      }
    });
    assertSame(Context.ROOT, Context.current());
  }

  @Test
  public void cancellableCtxApplier_callable_enters() throws Exception {
    Context.CancellableContext cancellableContext = makeCancellableContext();
    assertSame(Context.ROOT, Context.current());
    Contexts.cancellableContextApplier(cancellableContext).call(new Callable<Void>() {
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
    Context.CancellableContext cancellableContext = makeCancellableContext();
    assertSame(Context.ROOT, Context.current());
    @SuppressWarnings("unused")
    ListenableFuture<Integer> ignored = Contexts.cancellableContextApplier(cancellableContext)
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
    Context.CancellableContext cancellableContext = makeCancellableContext();
    Contexts.cancellableContextApplier(cancellableContext).run(new Runnable() {
      @Override
      public void run() {
      }
    });
    assertTrue(cancellableContext.isCancelled());
  }

  @Test
  public void cancellableCtxApplier_callable() throws Exception {
    Context.CancellableContext cancellableContext = makeCancellableContext();
    Contexts.cancellableContextApplier(cancellableContext).call(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        return null;
      }
    });
    assertTrue(cancellableContext.isCancelled());
  }

  @Test
  public void cancellableCtxApplier_asyncCallable_already_success() {
    Context.CancellableContext cancellableContext = makeCancellableContext();
    @SuppressWarnings("unused")
    ListenableFuture<Integer> ignored = Contexts.cancellableContextApplier(cancellableContext)
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
    Context.CancellableContext cancellableContext = makeCancellableContext();
    final FakeException fakeException = new FakeException();
    ListenableFuture<Integer> future = Contexts.cancellableContextApplier(cancellableContext)
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
    Context.CancellableContext cancellableContext = makeCancellableContext();
    ListenableFuture<Integer> ret = Contexts.cancellableContextApplier(cancellableContext)
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
    Context.CancellableContext cancellableContext = makeCancellableContext();
    final SettableFuture<Integer> settable = SettableFuture.create();
    @SuppressWarnings("unused")
    ListenableFuture<Integer> ignored = Contexts.cancellableContextApplier(cancellableContext)
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
    Context.CancellableContext cancellableContext = makeCancellableContext();
    ListenableFuture<Integer> ret = Contexts.cancellableContextApplier(cancellableContext)
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
    final Context.CancellableContext cancellableContext = makeCancellableContext();
    ListenableFuture<Integer> ret = Contexts.cancellableContextApplier(cancellableContext)
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
    Context.CancellableContext cancellableContext = makeCancellableContext();
    try {
      Contexts.cancellableContextApplier(cancellableContext).run(
          new Runnable() {
            @Override
            public void run() {
              throw new FakeException();
            }
          });
    } finally {
      assertTrue(cancellableContext.isCancelled());
    }
  }

  @Test(expected = FakeException.class)
  public void applier_callable_throws() throws Exception {
    Context.CancellableContext cancellableContext = makeCancellableContext();
    try {
      Contexts.cancellableContextApplier(cancellableContext).call(
          new Callable<Void>() {
            @Override
            public Void call() throws Exception {
              throw new FakeException();
            }
          }
      );
    } finally {
      assertTrue(cancellableContext.isCancelled());
    }
  }

  @Test
  public void applier_asyncCallable_throws() throws Exception {
    Context.CancellableContext cancellableContext = makeCancellableContext();
    final FakeException fakeException = new FakeException();
    ListenableFuture<Integer> future = Contexts.cancellableContextApplier(cancellableContext)
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

  private Context.CancellableContext makeCancellableContext() {
    return Context.current().withValue(contextKey, contextValue).withCancellation();
  }

  private void verifyInCancellableContext() {
    // Context.current() always returns an uncancellable surrogate, so it doesn't make sense to
    // check that the current context is a CancellableContext. So let's just make sure it looks
    // equivalent to the context created by makeCancelllableContext.
    assertSame(contextValue, contextKey.get());
  }

  /**
   * A class that lets us have a bit of type safety when testing thrown exceptions.
   */
  private static class FakeException extends RuntimeException { }
}
