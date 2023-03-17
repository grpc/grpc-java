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
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolverRegistry;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.testing.FakeNameResolverProvider;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.TestUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
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
  private static final String SERVER_TARGET_URI = "fake://server";

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

  final MethodDescriptor<String, String> bidiMethod =
      MethodDescriptor.newBuilder(StringMarshaller.INSTANCE, StringMarshaller.INSTANCE)
          .setFullMethodName("test/bidiMethod")
          .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
          .build();

  FakeNameResolverProvider fakeNameResolverProvider;
  ManagedChannel channel;
  AtomicReference<Metadata> headersCapture = new AtomicReference<>();
  AtomicReference<PeerUid> clientUidCapture = new AtomicReference<>();

  @Before
  public void setUp() throws Exception {
    ServerCallHandler<String, String> callHandler =
        ServerCalls.asyncUnaryCall(
            (req, respObserver) -> {
              clientUidCapture.set(PeerUids.REMOTE_PEER.get());
              respObserver.onNext(req);
              respObserver.onCompleted();
            });

    ServerCallHandler<String, String> singleLargeResultCallHandler =
        ServerCalls.asyncUnaryCall(
            (req, respObserver) -> {
              clientUidCapture.set(PeerUids.REMOTE_PEER.get());
              respObserver.onNext(createLargeString(SLIGHTLY_MORE_THAN_ONE_BLOCK));
              respObserver.onCompleted();
            });

    ServerCallHandler<String, String> bidiCallHandler =
        ServerCalls.asyncBidiStreamingCall(ForwardingStreamObserver::new); // Echo it all back.

    ServerServiceDefinition serviceDef =
        ServerInterceptors.intercept(
            ServerServiceDefinition.builder("test")
                .addMethod(method, callHandler)
                .addMethod(singleLargeResultMethod, singleLargeResultCallHandler)
                .addMethod(bidiMethod, bidiCallHandler)
                .build(),
            TestUtils.recordRequestHeadersInterceptor(headersCapture),
            PeerUids.newPeerIdentifyingServerInterceptor());

    AndroidComponentAddress serverAddress = HostServices.allocateService(appContext);
    fakeNameResolverProvider = new FakeNameResolverProvider(SERVER_TARGET_URI, serverAddress);
    NameResolverRegistry.getDefaultRegistry().register(fakeNameResolverProvider);
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
    NameResolverRegistry.getDefaultRegistry().deregister(fakeNameResolverProvider);
    HostServices.awaitServiceShutdown();
  }

  private ListenableFuture<String> doCall(String request) {
    return doCall(method, request);
  }

  private ListenableFuture<String> doCall(
      MethodDescriptor<String, String> methodDesc, String request) {
    return doCall(methodDesc, request, CallOptions.DEFAULT);
  }

  private ListenableFuture<String> doCall(
      MethodDescriptor<String, String> methodDesc, String request, CallOptions callOptions) {
    ListenableFuture<String> future =
        ClientCalls.futureUnaryCall(channel.newCall(methodDesc, callOptions), request);
    return withTestTimeout(future);
  }

  @Test
  public void testBasicCall() throws Exception {
    assertThat(doCall("Hello").get()).isEqualTo("Hello");
  }

  @Test
  public void testPeerUidIsRecorded() throws Exception {
    assertThat(doCall("Hello").get()).isEqualTo("Hello");
    assertThat(clientUidCapture.get()).isEqualTo(PeerUid.forCurrentProcess());
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

  @Test
  public void testSingleRequestCallOptionHeaders() throws Exception {
    CallOptions callOptions = CallOptions.DEFAULT.withDeadlineAfter(1234, MINUTES);
    assertThat(doCall(method, "Hello", callOptions).get()).isEqualTo("Hello");
    assertThat(headersCapture.get().get(GrpcUtil.TIMEOUT_KEY)).isGreaterThan(0);
  }

  @Test
  public void testStreamingCallOptionHeaders() throws Exception {
    CallOptions callOptions = CallOptions.DEFAULT.withDeadlineAfter(1234, MINUTES);
    QueueingStreamObserver<String> responseStreamObserver = new QueueingStreamObserver<>();
    StreamObserver<String> streamObserver =
        ClientCalls.asyncBidiStreamingCall(
            channel.newCall(bidiMethod, callOptions), responseStreamObserver);
    streamObserver.onCompleted();
    assertThat(withTestTimeout(responseStreamObserver.getAllStreamElements()).get()).isEmpty();
    assertThat(headersCapture.get().get(GrpcUtil.TIMEOUT_KEY)).isGreaterThan(0);
  }

  @Test
  public void testConnectViaTargetUri() throws Exception {
    channel = BinderChannelBuilder.forTarget(SERVER_TARGET_URI, appContext).build();
    assertThat(doCall("Hello").get()).isEqualTo("Hello");
  }

  private static String createLargeString(int size) {
    StringBuilder sb = new StringBuilder();
    while (sb.length() < size) {
      sb.append(MSG);
    }
    sb.setLength(size);
    return sb.toString();
  }

  private static <V> ListenableFuture<V> withTestTimeout(ListenableFuture<V> future) {
    return Futures.withTimeout(future, 5L, SECONDS, Executors.newSingleThreadScheduledExecutor());
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

  private static class QueueingStreamObserver<V> implements StreamObserver<V> {
    private final ArrayList<V> elements = new ArrayList<>();
    private final SettableFuture<Iterable<V>> result = SettableFuture.create();

    public ListenableFuture<Iterable<V>> getAllStreamElements() {
      return result;
    }

    @Override
    public void onNext(V value) {
      elements.add(value);
    }

    @Override
    public void onError(Throwable t) {
      result.setException(t);
    }

    @Override
    public void onCompleted() {
      result.set(elements);
    }
  }

  private static class ForwardingStreamObserver<V> implements StreamObserver<V> {
    private final StreamObserver<V> delegate;

    ForwardingStreamObserver(StreamObserver<V> delegate) {
      this.delegate = delegate;
    }

    @Override
    public void onNext(V value) {
      delegate.onNext(value);
    }

    @Override
    public void onError(Throwable t) {
      delegate.onError(t);
    }

    @Override
    public void onCompleted() {
      delegate.onCompleted();
    }
  }
}
