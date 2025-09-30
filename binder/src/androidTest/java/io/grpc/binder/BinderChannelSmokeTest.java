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
import android.content.Intent;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ConnectivityState;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.internal.GrpcUtil;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
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
import org.junit.Assert;
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
  private static final Metadata.Key<PoisonParcelable> POISON_KEY =
      ParcelableUtils.metadataKey("poison-bin", PoisonParcelable.CREATOR);

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

  ManagedChannel channel;
  AtomicReference<Metadata> headersCapture = new AtomicReference<>();
  AtomicReference<PeerUid> clientUidCapture = new AtomicReference<>();
  PoisonParcelable parcelableForResponseHeaders;

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
            new AddParcelableServerInterceptor(),
            TestUtils.recordRequestHeadersInterceptor(headersCapture),
            PeerUids.newPeerIdentifyingServerInterceptor());

    AndroidComponentAddress serverAddress = HostServices.allocateService(appContext);
    HostServices.configureService(
        serverAddress,
        HostServices.serviceParamsBuilder()
            .setServerFactory(
                (service, receiver) ->
                    BinderServerBuilder.forAddress(serverAddress, receiver)
                        .inboundParcelablePolicy(
                            InboundParcelablePolicy.newBuilder()
                                .setAcceptParcelableMetadataValues(true)
                                .build())
                        .addService(serviceDef)
                        .build())
            .build());

    channel =
        BinderChannelBuilder.forAddress(serverAddress, appContext)
            .inboundParcelablePolicy(
                InboundParcelablePolicy.newBuilder()
                    .setAcceptParcelableMetadataValues(true)
                    .build())
            .build();
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
    // Compare with the <intent-filter> mapping in AndroidManifest.xml.
    channel =
        BinderChannelBuilder.forTarget(
                "intent://authority/path#Intent;action=action1;scheme=scheme;end;", appContext)
            .build();
    assertThat(doCall("Hello").get()).isEqualTo("Hello");
  }

  @Test
  public void testConnectViaIntentFilter() throws Exception {
    // Compare with the <intent-filter> mapping in AndroidManifest.xml.
    channel =
        BinderChannelBuilder.forAddress(
                AndroidComponentAddress.forBindIntent(
                    new Intent()
                        .setAction("action1")
                        .setData(Uri.parse("scheme://authority/path"))
                        .setPackage(appContext.getPackageName())),
                appContext)
            .build();
    assertThat(doCall("Hello").get()).isEqualTo("Hello");
  }

  @Test
  public void testUncaughtServerException() throws Exception {
    // Use a poison parcelable to cause an unexpected Exception in the server's onTransact().
    PoisonParcelable bad = new PoisonParcelable();
    Metadata extraHeadersToSend = new Metadata();
    extraHeadersToSend.put(POISON_KEY, bad);
    Channel interceptedChannel =
        ClientInterceptors.intercept(
            channel, MetadataUtils.newAttachHeadersInterceptor(extraHeadersToSend));
    CallOptions callOptions = CallOptions.DEFAULT.withDeadlineAfter(5, SECONDS);
    try {
      ClientCalls.blockingUnaryCall(interceptedChannel, method, callOptions, "hello");
      Assert.fail();
    } catch (StatusRuntimeException e) {
      // We don't care how *our* RPC failed, but make sure we didn't have to rely on the deadline.
      assertThat(e.getStatus().getCode()).isNotEqualTo(Code.DEADLINE_EXCEEDED);
      assertThat(channel.getState(false)).isEqualTo(ConnectivityState.IDLE);
    }
  }

  @Test
  public void testUncaughtClientException() throws Exception {
    // Use a poison parcelable to cause an unexpected Exception in the client's onTransact().
    parcelableForResponseHeaders = new PoisonParcelable();
    CallOptions callOptions = CallOptions.DEFAULT.withDeadlineAfter(5, SECONDS);
    try {
      ClientCalls.blockingUnaryCall(channel, method, callOptions, "hello");
      Assert.fail();
    } catch (StatusRuntimeException e) {
      // We don't care *how* our RPC failed, but make sure we didn't have to rely on the deadline.
      assertThat(e.getStatus().getCode()).isNotEqualTo(Code.DEADLINE_EXCEEDED);
      assertThat(channel.getState(false)).isEqualTo(ConnectivityState.IDLE);
    }
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

  class AddParcelableServerInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      return next.startCall(
          new SimpleForwardingServerCall<ReqT, RespT>(call) {
            @Override
            public void sendHeaders(Metadata headers) {
              if (parcelableForResponseHeaders != null) {
                headers.put(POISON_KEY, parcelableForResponseHeaders);
              }
              super.sendHeaders(headers);
            }
          },
          headers);
    }
  }

  static class PoisonParcelable implements Parcelable {

    public static final Creator<PoisonParcelable> CREATOR =
        new Parcelable.Creator<PoisonParcelable>() {
          @Override
          public PoisonParcelable createFromParcel(Parcel parcel) {
            throw new RuntimeException("ouch");
          }

          @Override
          public PoisonParcelable[] newArray(int n) {
            return new PoisonParcelable[n];
          }
        };

    @Override
    public int describeContents() {
      return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {}
  }
}
