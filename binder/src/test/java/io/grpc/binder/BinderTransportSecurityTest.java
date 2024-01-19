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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.protobuf.Empty;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.lite.ProtoLiteUtils;

import androidx.lifecycle.LifecycleService;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;

import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCallHandler;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;

import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
public final class BinderTransportSecurityTest {

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
    channel = BinderChannelBuilder.forAddress(listenAddress, context).build();
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

    assertThat(status.get().getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
  }

  @Test
  public void testAsyncServerSecurityPolicy_allowed_returnsOkStatus() throws Exception {
    ListenableFuture<Status> status = makeCall();
    service.setSecurityPolicyStatusWhenReady(Status.OK);
    idleLoopers();

    assertThat(status.get().getCode()).isEqualTo(Status.Code.OK);
  }

  private void idleLoopers() {
    service.idleLooper();
    shadowOf(Looper.getMainLooper()).idle();
  }

  private ListenableFuture<Status> makeCall() {
    ClientCall<Empty, Empty> call =
        channel.newCall(
            getMethodDescriptor(),
            CallOptions.DEFAULT.withExecutor(service.getExecutor()));
    ListenableFuture<Empty> responseFuture =
        ClientCalls.futureUnaryCall(call, Empty.getDefaultInstance());

    idleLoopers();

    return Futures.catching(
        Futures.transform(responseFuture, unused -> Status.OK, directExecutor()),
        StatusRuntimeException.class,
        StatusRuntimeException::getStatus,
        directExecutor());
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
    private final ArrayBlockingQueue<SettableFuture<Status>> statuses =
        new ArrayBlockingQueue<>(128);
    private Server server;
    private HandlerThread handlerThread;
    private Handler handler;

    @Override
    public void onCreate() {
      super.onCreate();
      handlerThread = new HandlerThread("test_handler_thread");
      handlerThread.start();
      handler = new Handler(handlerThread.getLooper());

      MethodDescriptor<Empty, Empty> methodDesc = getMethodDescriptor();
      ServerCallHandler<Empty, Empty> callHandler =
          ServerCalls.asyncUnaryCall(
              (req, respObserver) -> {
                respObserver.onNext(req);
                respObserver.onCompleted();
              });
      ServerMethodDefinition<Empty, Empty> methodDef =
          ServerMethodDefinition.create(methodDesc, callHandler);
      ServerServiceDefinition def = ServerServiceDefinition.builder(SERVICE_NAME)
          .addMethod(methodDef)
          .build();

      server = BinderServerBuilder.forAddress(
              AndroidComponentAddress.forContext(this),
              binderReceiver)
          .addService(def)
          .securityPolicy(ServerSecurityPolicy.newBuilder()
              .servicePolicy(SERVICE_NAME, new AsyncSecurityPolicy() {
                @Override
                ListenableFuture<Status> checkAuthorizationAsync(int uid) {
                  return Futures.submitAsync(() -> {
                    SettableFuture<Status> status = SettableFuture.create();
                    statuses.add(status);
                    return status;
                  }, getExecutor());
                }
              })
              .build())
          .executor(getExecutor())
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
     * Returns an {@link Executor} under which all of the gRPC computations run under. The execution
     * of any pending tasks on this executor can be triggered via {@link #idleLooper()}.
     */
    Executor getExecutor() {
      return handler::post;
    }

    void idleLooper() {
      shadowOf(handlerThread.getLooper()).idle();
    }

    void setSecurityPolicyStatusWhenReady(Status status) {
      Uninterruptibles.takeUninterruptibly(statuses).set(status);
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
      handlerThread.quit();
    }
  }
}
