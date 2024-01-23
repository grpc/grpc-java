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

package io.grpc.binder;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.lifecycle.LifecycleService;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.Empty;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCallHandler;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.lite.ProtoLiteUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;

@RunWith(RobolectricTestRunner.class)
public final class RobolectricBinderSecurityTest {

  private static final String SERVICE_NAME = "fake_service";
  private static final String FULL_METHOD_NAME = "fake_service/fake_method";
  private final Application context = ApplicationProvider.getApplicationContext();
  private ServiceController<SomeService> controller;
  private SomeService service;
  private ManagedChannel channel;

  @Before
  public void setUp() {
    controller = Robolectric.buildService(SomeService.class);
    service = controller.create().get();

    AndroidComponentAddress listenAddress = AndroidComponentAddress.forContext(service);
    ScheduledExecutorService executor = service.getExecutor();
    channel =
        BinderChannelBuilder.forAddress(listenAddress, context)
            .executor(executor)
            .scheduledExecutorService(executor)
            .offloadExecutor(executor)
            .build();
    idleLoopers();
  }

  @After
  public void tearDown() {
    channel.shutdownNow();
    controller.destroy();
  }

  @Test
  public void testAsyncServerSecurityPolicy_failed_returnsFailureStatus() throws Exception {
    ListenableFuture<Status> status = makeCall();
    service.setSecurityPolicyStatusWhenReady(Status.ALREADY_EXISTS);
    idleLoopers();

    assertThat(Futures.getDone(status).getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
  }

  @Test
  public void testAsyncServerSecurityPolicy_failedFuture_failsWithCodeInternal() throws Exception {
    ListenableFuture<Status> status = makeCall();
    service.setSecurityPolicyFailed(new IllegalStateException("oops"));
    idleLoopers();

    assertThat(Futures.getDone(status).getCode()).isEqualTo(Status.Code.INTERNAL);
  }

  @Test
  public void testAsyncServerSecurityPolicy_allowed_returnsOkStatus() throws Exception {
    ListenableFuture<Status> status = makeCall();
    service.setSecurityPolicyStatusWhenReady(Status.OK);
    idleLoopers();

    assertThat(Futures.getDone(status).getCode()).isEqualTo(Status.Code.OK);
  }

  private ListenableFuture<Status> makeCall() {
    ClientCall<Empty, Empty> call =
        channel.newCall(
            getMethodDescriptor(), CallOptions.DEFAULT.withExecutor(service.getExecutor()));
    ListenableFuture<Empty> responseFuture =
        ClientCalls.futureUnaryCall(call, Empty.getDefaultInstance());

    idleLoopers();

    return Futures.catching(
        Futures.transform(responseFuture, unused -> Status.OK, directExecutor()),
        StatusRuntimeException.class,
        StatusRuntimeException::getStatus,
        directExecutor());
  }

  private static void idleLoopers() {
    shadowOf(Looper.getMainLooper()).idle();
  }

  private static MethodDescriptor<Empty, Empty> getMethodDescriptor() {
    MethodDescriptor.Marshaller<Empty> marshaller =
        ProtoLiteUtils.marshaller(Empty.getDefaultInstance());

    return MethodDescriptor.newBuilder(marshaller, marshaller)
        .setFullMethodName(FULL_METHOD_NAME)
        .setType(MethodDescriptor.MethodType.UNARY)
        .setSampledToLocalTracing(true)
        .build();
  }

  private static class SomeService extends LifecycleService {

    private final IBinderReceiver binderReceiver = new IBinderReceiver();
    private final ArrayBlockingQueue<SettableFuture<Status>> statusesToSet =
        new ArrayBlockingQueue<>(128);
    private Server server;
    private final ScheduledExecutorService scheduledExecutorService =
        new HandlerScheduledExecutorService();

    @Override
    public void onCreate() {
      super.onCreate();

      MethodDescriptor<Empty, Empty> methodDesc = getMethodDescriptor();
      ServerCallHandler<Empty, Empty> callHandler =
          ServerCalls.asyncUnaryCall(
              (req, respObserver) -> {
                respObserver.onNext(req);
                respObserver.onCompleted();
              });
      ServerMethodDefinition<Empty, Empty> methodDef =
          ServerMethodDefinition.create(methodDesc, callHandler);
      ServerServiceDefinition def =
          ServerServiceDefinition.builder(SERVICE_NAME).addMethod(methodDef).build();

      server =
          BinderServerBuilder.forAddress(AndroidComponentAddress.forContext(this), binderReceiver)
              .addService(def)
              .securityPolicy(
                  ServerSecurityPolicy.newBuilder()
                      .servicePolicy(
                          SERVICE_NAME,
                          new AsyncSecurityPolicy() {
                            @Override
                            public ListenableFuture<Status> checkAuthorizationAsync(int uid) {
                              return Futures.submitAsync(
                                  () -> {
                                    SettableFuture<Status> status = SettableFuture.create();
                                    statusesToSet.add(status);
                                    return status;
                                  },
                                  getExecutor());
                            }
                          })
                      .build())
              .executor(getExecutor())
              .scheduledExecutorService(getExecutor())
              .build();
      try {
        server.start();
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }

      Application context = ApplicationProvider.getApplicationContext();
      ComponentName componentName = new ComponentName(context, SomeService.class);
      shadowOf(context)
          .setComponentNameAndServiceForBindService(
              componentName, checkNotNull(binderReceiver.get()));
    }

    /**
     * Returns an {@link ScheduledExecutorService} under which all of the gRPC computations run. The
     * execution of any pending tasks on this executor can be triggered via {@link #idleLoopers()}.
     */
    ScheduledExecutorService getExecutor() {
      return scheduledExecutorService;
    }

    void setSecurityPolicyStatusWhenReady(Status status) {
      getNextEnqueuedStatus().set(status);
    }

    void setSecurityPolicyFailed(Exception e) {
      getNextEnqueuedStatus().setException(e);
    }

    private SettableFuture<Status> getNextEnqueuedStatus() {
      @Nullable SettableFuture<Status> future = statusesToSet.poll();
      while (future == null) {
        // Keep idling until the future is available.
        idleLoopers();
        future = statusesToSet.poll();
      }
      return checkNotNull(future);
    }

    @Override
    public IBinder onBind(Intent intent) {
      super.onBind(intent);
      return checkNotNull(binderReceiver.get());
    }

    @Override
    public void onDestroy() {
      super.onDestroy();
      server.shutdownNow();
    }

    /** A future representing a task submitted to a {@link Handler}. */
    private static class HandlerFuture<V> implements ScheduledFuture<V> {

      private final Duration delay;
      private final SettableFuture<V> delegate = SettableFuture.create();

      HandlerFuture(Duration delay) {
        this.delay = delay;
      }

      @Override
      public long getDelay(TimeUnit timeUnit) {
        return timeUnit.convert(delay.toMillis(), TimeUnit.MILLISECONDS);
      }

      @Override
      public int compareTo(Delayed other) {
        return Comparator.comparingLong(
                (Delayed delayed) -> delayed.getDelay(TimeUnit.MILLISECONDS))
            .compare(this, other);
      }

      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        return delegate.cancel(mayInterruptIfRunning);
      }

      @Override
      public boolean isCancelled() {
        return delegate.isCancelled();
      }

      @Override
      public boolean isDone() {
        return delegate.isDone();
      }

      @Override
      public V get() throws ExecutionException, InterruptedException {
        return delegate.get();
      }

      @Override
      public V get(long timeout, TimeUnit timeUnit)
          throws ExecutionException, InterruptedException, TimeoutException {
        return delegate.get(timeout, timeUnit);
      }

      void complete(V result) {
        delegate.set(result);
      }

      void setException(Exception e) {
        delegate.setException(e);
      }
    }

    /**
     * Minimal implementation of a {@link ScheduledExecutorService} that delegates tasks to a {@link
     * Handler}. Pending tasks can be forced to run via {@link
     * org.robolectric.shadows.ShadowLooper#idle()}.
     */
    private static class HandlerScheduledExecutorService extends AbstractExecutorService
        implements ScheduledExecutorService {
      private final Handler handler = new Handler(Looper.getMainLooper());

      private static Runnable asRunnableFor(HandlerFuture<Void> future, Runnable runnable) {
        return () -> {
          try {
            runnable.run();
            future.complete(null);
          } catch (Exception e) {
            future.setException(e);
          }
        };
      }

      private static <V> Runnable asRunnableFor(HandlerFuture<V> future, Callable<V> callable) {
        return () -> {
          try {
            future.complete(callable.call());
          } catch (Exception e) {
            future.setException(e);
          }
        };
      }

      @Override
      public ScheduledFuture<?> schedule(Runnable runnable, long l, TimeUnit timeUnit) {
        long millis = timeUnit.toMillis(l);
        HandlerFuture<Void> result = new HandlerFuture<>(Duration.ofMillis(millis));
        handler.postDelayed(asRunnableFor(result, runnable), millis);
        return result;
      }

      @Override
      public <V> ScheduledFuture<V> schedule(Callable<V> callable, long l, TimeUnit timeUnit) {
        long millis = timeUnit.toMillis(l);
        HandlerFuture<V> result = new HandlerFuture<>(Duration.ofMillis(millis));
        handler.postDelayed(asRunnableFor(result, callable), millis);
        return result;
      }

      @Override
      public ScheduledFuture<?> scheduleAtFixedRate(
          Runnable runnable, long delay, long period, TimeUnit timeUnit) {
        return scheduleWithFixedDelay(runnable, delay, period, timeUnit);
      }

      @Override
      public ScheduledFuture<?> scheduleWithFixedDelay(
          Runnable runnable, long initialDelay, long delay, TimeUnit timeUnit) {
        long initialDelayMillis = timeUnit.toMillis(initialDelay);
        long periodMillis = timeUnit.toMillis(delay);
        HandlerFuture<Void> result = new HandlerFuture<>(Duration.ofMillis(initialDelayMillis));

        Runnable scheduledRunnable =
            new Runnable() {
              @Override
              public void run() {
                try {
                  runnable.run();
                  handler.postDelayed(this, periodMillis);
                } catch (Exception e) {
                  result.setException(e);
                }
              }
            };

        handler.postDelayed(scheduledRunnable, initialDelayMillis);
        return result;
      }

      @Override
      public void shutdown() {}

      @Override
      public List<Runnable> shutdownNow() {
        return ImmutableList.of();
      }

      @Override
      public boolean isShutdown() {
        return false;
      }

      @Override
      public boolean isTerminated() {
        return false;
      }

      @Override
      public boolean awaitTermination(long l, TimeUnit timeUnit) {
        idleLoopers();
        return true;
      }

      @Override
      public void execute(Runnable runnable) {
        handler.post(runnable);
      }
    }
  }
}
