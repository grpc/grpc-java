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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import android.app.Application;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Basic tests for Binder Channel, covering some of the edge cases not exercised by
 * AbstractTransportTest.
 */
@RunWith(AndroidJUnit4.class)
public final class BinderChannelSmokeTest {
  private final Context appContext = ApplicationProvider.getApplicationContext();

  private static final int SLIGHTLY_MORE_THAN_ONE_BLOCK = 16 * 1024 + 100;
  private static final String MSG = "Some text which will be repeated many many times";

  final MethodDescriptor<String, String> method =
      MethodDescriptor.newBuilder(StringMarshaller.INSTANCE, StringMarshaller.INSTANCE)
          .setFullMethodName("test/method")
          .setType(MethodDescriptor.MethodType.UNARY)
          .build();

  final MethodDescriptor<String, String> singleLargeResultMethod =
      MethodDescriptor.newBuilder(StringMarshaller.INSTANCE, StringMarshaller.INSTANCE)
          .setFullMethodName("test/noResultMethod")
          .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
          .build();

  AndroidComponentAddress serverAddress;
  ManagedChannel channel;

  @Before
  public void setUp() throws Exception {
    ServerCallHandler<String, String> callHandler =
        ServerCalls.asyncUnaryCall(
            (req, respObserver) -> {
              respObserver.onNext(req);
              respObserver.onCompleted();
            });

    ServerCallHandler<String, String> singleLargeResultCallHandler =
        ServerCalls.asyncUnaryCall(
            (req, respObserver) -> {
              respObserver.onNext(createLargeString(SLIGHTLY_MORE_THAN_ONE_BLOCK));
              respObserver.onCompleted();
            });

    ServerServiceDefinition serviceDef =
        ServerServiceDefinition.builder("test")
            .addMethod(method, callHandler)
            .addMethod(singleLargeResultMethod, singleLargeResultCallHandler)
            .build();

    AndroidComponentAddress serverAddress = HostServices.allocateService(appContext);
    HostServices.configureService(serverAddress,
        HostServices.serviceParamsBuilder()
          .setServerFactory((service, receiver) ->
              BinderServerBuilder.forAddress(serverAddress, receiver)
                .addService(serviceDef)
                .build())
          .build());

    channel = BinderChannelBuilder.forAddress(serverAddress, appContext).build();
  }

  @After
  public void tearDown() throws Exception {
    channel.shutdownNow();
    HostServices.awaitServiceShutdown();
  }

  private ListenableFuture<String> doCall(String request) {
    return doCall(method, request);
  }

  private ListenableFuture<String> doCall(
      MethodDescriptor<String, String> methodDesc, String request) {
    ListenableFuture<String> future =
        ClientCalls.futureUnaryCall(channel.newCall(methodDesc, CallOptions.DEFAULT), request);
    return Futures.withTimeout(
        future, 5L, TimeUnit.SECONDS, Executors.newSingleThreadScheduledExecutor());
  }

  @Test
  public void testBasicCall() throws Exception {
    assertThat(doCall("Hello").get()).isEqualTo("Hello");
  }

  @Test
  public void testEmptyMessage() throws Exception {
    assertThat(doCall("").get()).isEmpty();
  }

  @Test
  public void test100kString() throws Exception {
    String fullMsg = createLargeString(100000);
    assertThat(doCall(fullMsg).get()).isEqualTo(fullMsg);
  }

  @Test
  public void testSingleLargeResultCall() throws Exception {
    String res = doCall(singleLargeResultMethod, "hello").get();
    assertThat(res.length()).isEqualTo(SLIGHTLY_MORE_THAN_ONE_BLOCK);
  }

  private static String createLargeString(int size) {
    StringBuilder sb = new StringBuilder();
    while (sb.length() < size) {
      sb.append(MSG);
    }
    sb.setLength(size);
    return sb.toString();
  }

  private static class StringMarshaller implements MethodDescriptor.Marshaller<String> {
    public static final StringMarshaller INSTANCE = new StringMarshaller();

    @Override
    public InputStream stream(String value) {
      return new ByteArrayInputStream(value.getBytes(UTF_8));
    }

    @Override
    public String parse(InputStream stream) {
      try {
        return new String(ByteStreams.toByteArray(stream), UTF_8);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }
  }
}
