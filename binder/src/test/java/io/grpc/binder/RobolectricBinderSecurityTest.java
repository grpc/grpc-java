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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.core.content.pm.ApplicationInfoBuilder;
import androidx.test.core.content.pm.PackageInfoBuilder;
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
import java.util.concurrent.ArrayBlockingQueue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.LooperMode.Mode;

@RunWith(ParameterizedRobolectricTestRunner.class)
@LooperMode(Mode.INSTRUMENTATION_TEST)
public final class RobolectricBinderSecurityTest {

  private static final String SERVICE_NAME = "fake_service";
  private static final String FULL_METHOD_NAME = "fake_service/fake_method";
  private final Application context = ApplicationProvider.getApplicationContext();
  private final ArrayBlockingQueue<SettableFuture<Status>> statusesToSet =
      new ArrayBlockingQueue<>(128);
  private ManagedChannel channel;
  private Server server;

  @Parameter public boolean preAuthServersParam;

  @Parameters(name = "preAuthServersParam={0}")
  public static ImmutableList<Boolean> data() {
    return ImmutableList.of(true, false);
  }

  @Before
  public void setUp() {
    ApplicationInfo serverAppInfo = ApplicationInfoBuilder.newBuilder()
        .setPackageName(context.getPackageName())
        .build();
    serverAppInfo.uid = android.os.Process.myUid();
    PackageInfo serverPkgInfo = PackageInfoBuilder.newBuilder()
        .setPackageName(serverAppInfo.packageName)
        .setApplicationInfo(serverAppInfo)
        .build();
    shadowOf(context.getPackageManager()).installPackage(serverPkgInfo);

    ServiceInfo serviceInfo = new ServiceInfo();
    serviceInfo.name = "SomeService";
    serviceInfo.packageName = serverAppInfo.packageName;
    serviceInfo.applicationInfo = serverAppInfo;
    shadowOf(context.getPackageManager()).addOrUpdateService(serviceInfo);

    AndroidComponentAddress listenAddress = AndroidComponentAddress.forRemoteComponent(
        serviceInfo.packageName, serviceInfo.name);

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

    IBinderReceiver binderReceiver = new IBinderReceiver();
    server =
        BinderServerBuilder.forAddress(listenAddress, binderReceiver)
            .addService(def)
            .securityPolicy(
                ServerSecurityPolicy.newBuilder()
                    .servicePolicy(
                        SERVICE_NAME,
                        new AsyncSecurityPolicy() {
                          @Override
                          public ListenableFuture<Status> checkAuthorizationAsync(int uid) {
                            SettableFuture<Status> status = SettableFuture.create();
                            statusesToSet.add(status);
                            return status;
                          }
                        })
                    .build())
            .build();
    try {
      server.start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }

    shadowOf(context)
        .setComponentNameAndServiceForBindServiceForIntent(
            listenAddress.asBindIntent(),
            listenAddress.getComponent(),
            checkNotNull(binderReceiver.get()));
    channel =
        BinderChannelBuilder.forAddress(listenAddress, context)
            .preAuthorizeServers(preAuthServersParam)
            .build();
  }

  @After
  public void tearDown() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  public void testAsyncServerSecurityPolicy_failed_returnsFailureStatus() throws Exception {
    ListenableFuture<Status> status = makeCall();
    statusesToSet.take().set(Status.ALREADY_EXISTS);

    assertThat(status.get().getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
  }

  @Test
  public void testAsyncServerSecurityPolicy_failedFuture_failsWithCodeInternal() throws Exception {
    ListenableFuture<Status> status = makeCall();
    statusesToSet.take().setException(new IllegalStateException("oops"));

    assertThat(status.get().getCode()).isEqualTo(Status.Code.INTERNAL);
  }

  @Test
  public void testAsyncServerSecurityPolicy_allowed_returnsOkStatus() throws Exception {
    ListenableFuture<Status> status = makeCall();
    statusesToSet.take().set(Status.OK);

    assertThat(status.get().getCode()).isEqualTo(Status.Code.OK);
  }

  private ListenableFuture<Status> makeCall() {
    ClientCall<Empty, Empty> call = channel.newCall(getMethodDescriptor(), CallOptions.DEFAULT);
    ListenableFuture<Empty> responseFuture =
        ClientCalls.futureUnaryCall(call, Empty.getDefaultInstance());

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
}
