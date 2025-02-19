/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ClientStreamTracer.NAME_RESOLUTION_DELAYED;
import static io.grpc.internal.ClientStreamListener.RpcProgress.PROCESSED;
import static io.grpc.internal.GrpcUtil.ACCEPT_ENCODING_SPLITTER;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.Codec;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Decompressor;
import io.grpc.DecompressorRegistry;
import io.grpc.InternalConfigSelector;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.NoopClientCall;
import io.grpc.Status;
import io.grpc.internal.ClientCallImpl.ClientStreamProvider;
import io.grpc.internal.ManagedChannelServiceConfig.MethodInfo;
import io.grpc.internal.SingleMessageProducer;
import io.grpc.testing.TestMethodDescriptors;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/**
 * Test for {@link ClientCallImpl}.
 */
@RunWith(JUnit4.class)
public class ClientCallImplTest {

  private final FakeClock fakeClock = new FakeClock();
  private final ScheduledExecutorService deadlineCancellationExecutor =
      fakeClock.getScheduledExecutorService();
  private final CallTracer channelCallTracer = CallTracer.getDefaultFactory().create();
  private final DecompressorRegistry decompressorRegistry =
      DecompressorRegistry.getDefaultInstance().with(new Codec.Gzip(), true);
  private final MethodDescriptor<Void, Void> method = MethodDescriptor.<Void, Void>newBuilder()
      .setType(MethodType.UNARY)
      .setFullMethodName("service/method")
      .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
      .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
      .build();

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Captor private ArgumentCaptor<Status> statusCaptor;

  @Mock
  private ClientStreamTracer.Factory streamTracerFactory;

  @Mock
  private ClientStreamProvider clientStreamProvider;

  @Mock
  private ClientStream stream;

  private InternalConfigSelector configSelector = null;

  @Mock
  private ClientCall.Listener<Void> callListener;

  @Captor
  private ArgumentCaptor<ClientStreamListener> listenerArgumentCaptor;

  @Captor
  private ArgumentCaptor<Status> statusArgumentCaptor;

  private CallOptions baseCallOptions;

  @Before
  public void setUp() {
    when(clientStreamProvider.newStream(
            (MethodDescriptor<?, ?>) any(MethodDescriptor.class),
            any(CallOptions.class),
            any(Metadata.class),
            any(Context.class)))
        .thenReturn(stream);
    when(streamTracerFactory.newClientStreamTracer(any(StreamInfo.class), any(Metadata.class)))
        .thenReturn(new ClientStreamTracer() {});
    doAnswer(new Answer<Void>() {
        @Override
        public Void answer(InvocationOnMock in) {
          InsightBuilder insight = (InsightBuilder) in.getArguments()[0];
          insight.appendKeyValue("remote_addr", "127.0.0.1:443");
          return null;
        }
      }).when(stream).appendTimeoutInsight(any(InsightBuilder.class));
    baseCallOptions = CallOptions.DEFAULT.withStreamTracerFactory(streamTracerFactory);
  }

  @After
  public void tearDown() {
    verifyNoMoreInteractions(streamTracerFactory);
  }

  @Test
  public void statusPropagatedFromStreamToCallListener() {
    DelayedExecutor executor = new DelayedExecutor();
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        executor,
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);
    call.start(callListener, new Metadata());
    verify(stream).start(listenerArgumentCaptor.capture());
    final ClientStreamListener streamListener = listenerArgumentCaptor.getValue();
    streamListener.headersRead(new Metadata());
    Status status = Status.RESOURCE_EXHAUSTED.withDescription("simulated");
    streamListener.closed(status , PROCESSED, new Metadata());
    executor.release();

    verify(callListener).onClose(same(status), ArgumentMatchers.isA(Metadata.class));
  }

  @Test
  public void exceptionInOnMessageTakesPrecedenceOverServer() {
    DelayedExecutor executor = new DelayedExecutor();
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        executor,
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);
    call.start(callListener, new Metadata());
    verify(stream).start(listenerArgumentCaptor.capture());
    final ClientStreamListener streamListener = listenerArgumentCaptor.getValue();
    streamListener.headersRead(new Metadata());

    RuntimeException failure = new RuntimeException("bad");
    doThrow(failure).when(callListener).onMessage(ArgumentMatchers.<Void>any());

    /*
     * In unary calls, the server closes the call right after responding, so the onClose call is
     * queued to run.  When messageRead is called, an exception will occur and attempt to cancel the
     * stream.  However, since the server closed it "first" the second exception is lost leading to
     * the call being counted as successful.
     */
    streamListener
        .messagesAvailable(new SingleMessageProducer(new ByteArrayInputStream(new byte[]{})));
    streamListener.closed(Status.OK, PROCESSED, new Metadata());
    executor.release();

    verify(callListener).onClose(statusArgumentCaptor.capture(),
        ArgumentMatchers.isA(Metadata.class));
    Status callListenerStatus = statusArgumentCaptor.getValue();
    assertThat(callListenerStatus.getCode()).isEqualTo(Status.Code.CANCELLED);
    assertThat(callListenerStatus.getCause()).isSameInstanceAs(failure);
    verify(stream).cancel(same(callListenerStatus));
  }

  @Test
  public void exceptionInOnHeadersTakesPrecedenceOverServer() {
    DelayedExecutor executor = new DelayedExecutor();
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        executor,
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);
    call.start(callListener, new Metadata());
    verify(stream).start(listenerArgumentCaptor.capture());
    final ClientStreamListener streamListener = listenerArgumentCaptor.getValue();

    RuntimeException failure = new RuntimeException("bad");
    doThrow(failure).when(callListener).onHeaders(any(Metadata.class));

    /*
     * In unary calls, the server closes the call right after responding, so the onClose call is
     * queued to run.  When headersRead is called, an exception will occur and attempt to cancel the
     * stream.  However, since the server closed it "first" the second exception is lost leading to
     * the call being counted as successful.
     */
    streamListener.headersRead(new Metadata());
    streamListener.closed(Status.OK, PROCESSED, new Metadata());
    executor.release();

    verify(callListener).onClose(statusArgumentCaptor.capture(),
        ArgumentMatchers.isA(Metadata.class));
    Status callListenerStatus = statusArgumentCaptor.getValue();
    assertThat(callListenerStatus.getCode()).isEqualTo(Status.Code.CANCELLED);
    assertThat(callListenerStatus.getCause()).isSameInstanceAs(failure);
    verify(stream).cancel(same(callListenerStatus));
  }

  @Test
  public void exceptionInOnHeadersHasOnCloseQueuedLast() {
    class PointOfNoReturnExecutor implements Executor {
      boolean rejectNewRunnables;

      @Override public void execute(Runnable command) {
        assertThat(rejectNewRunnables).isFalse();
        command.run();
      }
    }

    final PointOfNoReturnExecutor executor = new PointOfNoReturnExecutor();
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        executor,
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);
    callListener = new NoopClientCall.NoopClientCallListener<Void>() {
      private final RuntimeException failure = new RuntimeException("bad");

      @Override public void onHeaders(Metadata metadata) {
        throw failure;
      }

      @Override public void onClose(Status status, Metadata metadata) {
        verify(stream).cancel(same(status));
        assertThat(status.getCode()).isEqualTo(Status.Code.CANCELLED);
        assertThat(status.getCause()).isSameInstanceAs(failure);
        // At the point onClose() is called the user may shut down the executor, so no further
        // Runnables may be scheduled. The only thread-safe way of guaranteeing that is for
        // onClose() to be queued last.
        executor.rejectNewRunnables = true;
      }
    };
    call.start(callListener, new Metadata());
    verify(stream).start(listenerArgumentCaptor.capture());
    final ClientStreamListener streamListener = listenerArgumentCaptor.getValue();

    streamListener.headersRead(new Metadata());
    streamListener.closed(Status.OK, PROCESSED, new Metadata());
  }

  @Test
  public void exceptionInOnReadyTakesPrecedenceOverServer() {
    DelayedExecutor executor = new DelayedExecutor();
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method.toBuilder().setType(MethodType.UNKNOWN).build(),
        executor,
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);
    call.start(callListener, new Metadata());
    verify(stream).start(listenerArgumentCaptor.capture());
    final ClientStreamListener streamListener = listenerArgumentCaptor.getValue();

    RuntimeException failure = new RuntimeException("bad");
    doThrow(failure).when(callListener).onReady();

    /*
     * In unary calls, the server closes the call right after responding, so the onClose call is
     * queued to run.  When onReady is called, an exception will occur and attempt to cancel the
     * stream.  However, since the server closed it "first" the second exception is lost leading to
     * the call being counted as successful.
     */
    streamListener.onReady();
    streamListener.closed(Status.OK, PROCESSED, new Metadata());
    executor.release();

    verify(callListener).onClose(statusArgumentCaptor.capture(),
        ArgumentMatchers.isA(Metadata.class));
    Status callListenerStatus = statusArgumentCaptor.getValue();
    assertThat(callListenerStatus.getCode()).isEqualTo(Status.Code.CANCELLED);
    assertThat(callListenerStatus.getCause()).isSameInstanceAs(failure);
    verify(stream).cancel(same(callListenerStatus));
  }

  @Test
  public void advertisedEncodingsAreSent() {
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector)
            .setDecompressorRegistry(decompressorRegistry);

    call.start(callListener, new Metadata());

    ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
    verify(clientStreamProvider).newStream(
        eq(method), same(baseCallOptions), metadataCaptor.capture(), any(Context.class));
    Metadata actual = metadataCaptor.getValue();

    // there should only be one.
    Set<String> acceptedEncodings = ImmutableSet.of(
        new String(actual.get(GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY), GrpcUtil.US_ASCII));
    assertEquals(decompressorRegistry.getAdvertisedMessageEncodings(), acceptedEncodings);
  }

  @Test
  public void authorityPropagatedToStream() {
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        baseCallOptions.withAuthority("overridden-authority"),
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector)
            .setDecompressorRegistry(decompressorRegistry);

    call.start(callListener, new Metadata());
    verify(stream).setAuthority("overridden-authority");
  }

  @Test
  public void callOptionsPropagatedToTransport() {
    final CallOptions callOptions = baseCallOptions.withAuthority("dummy_value");
    final ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        callOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector)
        .setDecompressorRegistry(decompressorRegistry);
    final Metadata metadata = new Metadata();

    call.start(callListener, metadata);

    verify(clientStreamProvider).newStream(
        same(method), same(callOptions), same(metadata), any(Context.class));
  }

  @Test
  public void methodInfoDeadlinePropagatedToStream() {
    ArgumentCaptor<CallOptions> callOptionsCaptor = ArgumentCaptor.forClass(CallOptions.class);
    CallOptions callOptions = baseCallOptions.withDeadline(Deadline.after(2000, SECONDS));

    // Case: config Deadline expires later than CallOptions Deadline
    Map<String, ?> rawMethodConfig = ImmutableMap.of("timeout", "3000s");
    MethodInfo methodInfo = new MethodInfo(rawMethodConfig, false, 0, 0);
    callOptions = callOptions.withOption(MethodInfo.KEY, methodInfo);
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        callOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector)
        .setDecompressorRegistry(decompressorRegistry);
    call.start(callListener, new Metadata());
    verify(clientStreamProvider).newStream(
        same(method), callOptionsCaptor.capture(), any(Metadata.class), any(Context.class));
    Deadline actualDeadline = callOptionsCaptor.getValue().getDeadline();
    assertThat(actualDeadline).isLessThan(Deadline.after(2001, SECONDS));

    // Case: config Deadline expires earlier than CallOptions Deadline
    rawMethodConfig = ImmutableMap.of("timeout", "1000s");
    methodInfo = new MethodInfo(rawMethodConfig, false, 0, 0);
    callOptions = callOptions.withOption(MethodInfo.KEY, methodInfo);
    call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        callOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector)
        .setDecompressorRegistry(decompressorRegistry);
    call.start(callListener, new Metadata());
    verify(clientStreamProvider, times(2)).newStream(
        same(method), callOptionsCaptor.capture(), any(Metadata.class), any(Context.class));
    actualDeadline = callOptionsCaptor.getValue().getDeadline();
    assertThat(actualDeadline).isLessThan(Deadline.after(1001, SECONDS));
  }

  @Test
  public void authorityNotPropagatedToStream() {
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        // Don't provide an authority
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector)
            .setDecompressorRegistry(decompressorRegistry);

    call.start(callListener, new Metadata());
    verify(stream, never()).setAuthority(any(String.class));
  }

  @Test
  public void prepareHeaders_userAgentIgnored() {
    Metadata m = new Metadata();
    m.put(GrpcUtil.USER_AGENT_KEY, "batmobile");
    ClientCallImpl.prepareHeaders(m, decompressorRegistry, Codec.Identity.NONE, false);

    // User Agent is removed and set by the transport
    assertThat(m.get(GrpcUtil.USER_AGENT_KEY)).isNotNull();
  }

  @Test
  public void prepareHeaders_ignoreIdentityEncoding() {
    Metadata m = new Metadata();
    ClientCallImpl.prepareHeaders(m, decompressorRegistry, Codec.Identity.NONE, false);

    assertNull(m.get(GrpcUtil.MESSAGE_ENCODING_KEY));
  }

  @Test
  public void prepareHeaders_ignoreContentLength() {
    Metadata m = new Metadata();
    m.put(GrpcUtil.CONTENT_LENGTH_KEY, "123");
    ClientCallImpl.prepareHeaders(m, decompressorRegistry, Codec.Identity.NONE, false);

    assertNull(m.get(GrpcUtil.CONTENT_LENGTH_KEY));
  }

  @Test
  public void prepareHeaders_acceptedMessageEncodingsAdded() {
    Metadata m = new Metadata();
    DecompressorRegistry customRegistry = DecompressorRegistry.emptyInstance()
        .with(new Decompressor() {
          @Override
          public String getMessageEncoding() {
            return "a";
          }

          @Override
          public InputStream decompress(InputStream is) throws IOException {
            return null;
          }
        }, true)
        .with(new Decompressor() {
          @Override
          public String getMessageEncoding() {
            return "b";
          }

          @Override
          public InputStream decompress(InputStream is) throws IOException {
            return null;
          }
        }, true)
        .with(new Decompressor() {
          @Override
          public String getMessageEncoding() {
            return "c";
          }

          @Override
          public InputStream decompress(InputStream is) throws IOException {
            return null;
          }
        }, false); // not advertised

    ClientCallImpl.prepareHeaders(m, customRegistry, Codec.Identity.NONE, false);

    Iterable<String> acceptedEncodings = ACCEPT_ENCODING_SPLITTER.split(
        new String(m.get(GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY), GrpcUtil.US_ASCII));

    // Order may be different, since decoder priorities have not yet been implemented.
    assertEquals(ImmutableSet.of("b", "a"), ImmutableSet.copyOf(acceptedEncodings));
  }

  @Test
  public void prepareHeaders_noAcceptedContentEncodingsWithoutFullStreamDecompressionEnabled() {
    Metadata m = new Metadata();
    ClientCallImpl.prepareHeaders(m, decompressorRegistry, Codec.Identity.NONE, false);

    assertNull(m.get(GrpcUtil.CONTENT_ACCEPT_ENCODING_KEY));
  }

  @Test
  public void prepareHeaders_acceptedMessageAndContentEncodingsAdded() {
    Metadata m = new Metadata();
    DecompressorRegistry customRegistry =
        DecompressorRegistry.emptyInstance()
            .with(
                new Decompressor() {
                  @Override
                  public String getMessageEncoding() {
                    return "a";
                  }

                  @Override
                  public InputStream decompress(InputStream is) throws IOException {
                    return null;
                  }
                },
                true)
            .with(
                new Decompressor() {
                  @Override
                  public String getMessageEncoding() {
                    return "b";
                  }

                  @Override
                  public InputStream decompress(InputStream is) throws IOException {
                    return null;
                  }
                },
                true)
            .with(
                new Decompressor() {
                  @Override
                  public String getMessageEncoding() {
                    return "c";
                  }

                  @Override
                  public InputStream decompress(InputStream is) throws IOException {
                    return null;
                  }
                },
                false); // not advertised

    ClientCallImpl.prepareHeaders(m, customRegistry, Codec.Identity.NONE, true);

    Iterable<String> acceptedMessageEncodings =
        ACCEPT_ENCODING_SPLITTER.split(
            new String(m.get(GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY), GrpcUtil.US_ASCII));
    // Order may be different, since decoder priorities have not yet been implemented.
    assertEquals(ImmutableSet.of("b", "a"), ImmutableSet.copyOf(acceptedMessageEncodings));

    Iterable<String> acceptedContentEncodings =
        ACCEPT_ENCODING_SPLITTER.split(
            new String(m.get(GrpcUtil.CONTENT_ACCEPT_ENCODING_KEY), GrpcUtil.US_ASCII));
    assertEquals(
        ImmutableSet.of("gzip"), ImmutableSet.copyOf(acceptedContentEncodings));
  }

  @Test
  public void prepareHeaders_removeReservedHeaders() {
    Metadata m = new Metadata();
    m.put(GrpcUtil.MESSAGE_ENCODING_KEY, "gzip");
    m.put(GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY, "gzip".getBytes(GrpcUtil.US_ASCII));
    m.put(GrpcUtil.CONTENT_ENCODING_KEY, "gzip");
    m.put(GrpcUtil.CONTENT_ACCEPT_ENCODING_KEY, "gzip".getBytes(GrpcUtil.US_ASCII));

    ClientCallImpl.prepareHeaders(
        m, DecompressorRegistry.emptyInstance(), Codec.Identity.NONE, false);

    assertNull(m.get(GrpcUtil.MESSAGE_ENCODING_KEY));
    assertNull(m.get(GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY));
    assertNull(m.get(GrpcUtil.CONTENT_ENCODING_KEY));
    assertNull(m.get(GrpcUtil.CONTENT_ACCEPT_ENCODING_KEY));
  }

  @Test
  public void callerContextPropagatedToListener() throws Exception {
    // Attach the context which is recorded when the call is created
    final Context.Key<String> testKey = Context.key("testing");
    Context context = Context.current().withValue(testKey, "testValue");
    Context previous = context.attach();

    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method.toBuilder().setType(MethodType.UNKNOWN).build(),
        new SerializingExecutor(Executors.newSingleThreadExecutor()),
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector)
            .setDecompressorRegistry(decompressorRegistry);

    context.detach(previous);

    // Override the value after creating the call, this should not be seen by callbacks
    context = Context.current().withValue(testKey, "badValue");
    previous = context.attach();

    final AtomicBoolean onHeadersCalled = new AtomicBoolean();
    final AtomicBoolean onMessageCalled = new AtomicBoolean();
    final AtomicBoolean onReadyCalled = new AtomicBoolean();
    final AtomicBoolean observedIncorrectContext = new AtomicBoolean();
    final CountDownLatch latch = new CountDownLatch(1);

    call.start(new ClientCall.Listener<Void>() {
      @Override
      public void onHeaders(Metadata headers) {
        onHeadersCalled.set(true);
        checkContext();
      }

      @Override
      public void onMessage(Void message) {
        onMessageCalled.set(true);
        checkContext();
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        checkContext();
        latch.countDown();
      }

      @Override
      public void onReady() {
        onReadyCalled.set(true);
        checkContext();
      }

      private void checkContext() {
        if (!"testValue".equals(testKey.get())) {
          observedIncorrectContext.set(true);
        }
      }
    }, new Metadata());

    context.detach(previous);

    verify(stream).start(listenerArgumentCaptor.capture());
    ClientStreamListener listener = listenerArgumentCaptor.getValue();
    listener.onReady();
    listener.headersRead(new Metadata());
    listener.messagesAvailable(new SingleMessageProducer(new ByteArrayInputStream(new byte[0])));
    listener.messagesAvailable(new SingleMessageProducer(new ByteArrayInputStream(new byte[0])));
    listener.closed(Status.OK, PROCESSED, new Metadata());

    assertTrue(latch.await(5, TimeUnit.SECONDS));

    assertTrue(onHeadersCalled.get());
    assertTrue(onMessageCalled.get());
    assertTrue(onReadyCalled.get());
    assertFalse(observedIncorrectContext.get());
  }

  @Test
  public void contextCancellationCancelsStream() throws Exception {
    // Attach the context which is recorded when the call is created
    Context.CancellableContext cancellableContext = Context.current().withCancellation();
    Context previous = cancellableContext.attach();

    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        new SerializingExecutor(Executors.newSingleThreadExecutor()),
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector)
            .setDecompressorRegistry(decompressorRegistry);

    cancellableContext.detach(previous);

    call.start(callListener, new Metadata());

    Throwable t = new Throwable();
    cancellableContext.cancel(t);

    verify(stream, times(1)).cancel(statusArgumentCaptor.capture());
    Status streamStatus = statusArgumentCaptor.getValue();
    assertEquals(Status.Code.CANCELLED, streamStatus.getCode());
  }

  @Test
  public void contextAlreadyCancelledNotifiesImmediately() throws Exception {
    // Attach the context which is recorded when the call is created
    Context.CancellableContext cancellableContext = Context.current().withCancellation();
    Throwable cause = new Throwable();
    cancellableContext.cancel(cause);
    Context previous = cancellableContext.attach();

    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        new SerializingExecutor(Executors.newSingleThreadExecutor()),
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector)
        .setDecompressorRegistry(decompressorRegistry);

    cancellableContext.detach(previous);

    final SettableFuture<Status> statusFuture = SettableFuture.create();
    call.start(new ClientCall.Listener<Void>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        statusFuture.set(status);
      }
    }, new Metadata());

    // Caller should receive onClose callback.
    Status status = statusFuture.get(5, TimeUnit.SECONDS);
    assertEquals(Status.Code.CANCELLED, status.getCode());
    assertSame(cause, status.getCause());

    // Following operations should be no-op.
    call.request(1);
    call.sendMessage(null);
    call.halfClose();

    // Stream should never be created.
    verifyNoInteractions(clientStreamProvider);

    try {
      call.sendMessage(null);
      fail("Call has been cancelled");
    } catch (IllegalStateException ise) {
      // expected
    }
  }

  @Test
  public void deadlineExceededBeforeCallStarted() {
    deadlineExeedeed(baseCallOptions.withDeadlineAfter(0, TimeUnit.SECONDS),
        "Name resolution delay 0.000000000 seconds.");
  }

  @Test
  public void deadlineExceededBeforeCallStartedDelayed() {
    deadlineExeedeed(baseCallOptions.withDeadlineAfter(0, TimeUnit.SECONDS)
            .withOption(NAME_RESOLUTION_DELAYED, 1200000000L),
        "Name resolution delay 1.200000000 seconds.");
  }

  private void deadlineExeedeed(CallOptions callOptions, String descriptionSuffix) {
    fakeClock.forwardTime(System.nanoTime(), TimeUnit.NANOSECONDS);
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        new SerializingExecutor(Executors.newSingleThreadExecutor()),
        callOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector)
            .setDecompressorRegistry(decompressorRegistry);
    call.start(callListener, new Metadata());
    verify(streamTracerFactory).newClientStreamTracer(any(StreamInfo.class), any(Metadata.class));
    verify(clientStreamProvider, never())
        .newStream(
            (MethodDescriptor<?, ?>) any(MethodDescriptor.class),
            any(CallOptions.class),
            any(Metadata.class),
            any(Context.class));
    verify(callListener, timeout(1000)).onClose(statusCaptor.capture(), any(Metadata.class));
    assertEquals(Status.Code.DEADLINE_EXCEEDED, statusCaptor.getValue().getCode());
    String deadlineExceedDescription = statusCaptor.getValue().getDescription();
    assertThat(deadlineExceedDescription)
        .startsWith("ClientCall started after CallOptions deadline was exceeded");
    assertThat(deadlineExceedDescription).endsWith(descriptionSuffix);
    verifyNoInteractions(clientStreamProvider);
  }

  @Test
  public void contextDeadlineShouldBePropagatedToStream() {
    Deadline deadline = Deadline.after(1000, TimeUnit.MILLISECONDS);
    Context context = Context.current().withDeadline(deadline, deadlineCancellationExecutor);
    Context origContext = context.attach();

    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);
    call.start(callListener, new Metadata());

    context.detach(origContext);

    verify(stream).setDeadline(eq(deadline));
  }

  @Test
  public void contextDeadlineShouldOverrideLargerCallOptionsDeadline() {
    Deadline deadline = Deadline.after(1000, TimeUnit.MILLISECONDS);
    Context context = Context.current().withDeadline(deadline, deadlineCancellationExecutor);
    Context origContext = context.attach();

    CallOptions callOpts = baseCallOptions.withDeadlineAfter(2000, TimeUnit.MILLISECONDS);
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        callOpts,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);
    call.start(callListener, new Metadata());

    context.detach(origContext);

    verify(stream).setDeadline(eq(deadline));
  }

  @Test
  public void contextDeadlineShouldNotOverrideSmallerCallOptionsDeadline() {
    Context context = Context.current()
        .withDeadlineAfter(2000, TimeUnit.MILLISECONDS, deadlineCancellationExecutor);
    Deadline deadline = Deadline.after(1000, TimeUnit.MILLISECONDS);
    Context origContext = context.attach();

    CallOptions callOpts = baseCallOptions.withDeadline(deadline)
        .withOption(NAME_RESOLUTION_DELAYED, 1200000000L);
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        callOpts,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);
    call.start(callListener, new Metadata());

    context.detach(origContext);

    verify(stream).setDeadline(eq(deadline));

    fakeClock.forwardNanos(TimeUnit.MILLISECONDS.toNanos(1000));
    verify(stream, timeout(1000)).cancel(statusCaptor.capture());
    String deadlineExceedDescription = statusCaptor.getValue().getDescription();
    assertThat(deadlineExceedDescription)
        .contains("Name resolution delay 1.200000000 seconds.");
  }

  @Test
  public void callOptionsDeadlineShouldBePropagatedToStream() {
    Deadline deadline = Deadline.after(1000, TimeUnit.MILLISECONDS);
    CallOptions callOpts = baseCallOptions.withDeadline(deadline);
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        callOpts,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);
    call.start(callListener, new Metadata());

    verify(stream).setDeadline(eq(deadline));
  }

  @Test
  public void noDeadlineShouldBePropagatedToStream() {
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);
    call.start(callListener, new Metadata());

    verify(stream, never()).setDeadline(any(Deadline.class));
  }

  @Test
  public void expiredDeadlineCancelsStream_CallOptions() {
    fakeClock.forwardTime(System.nanoTime(), TimeUnit.NANOSECONDS);
    // The deadline needs to be a number large enough to get encompass the call to start, otherwise
    // the scheduled cancellation won't be created, and the call will fail early.
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        baseCallOptions.withDeadline(
            Deadline.after(1, TimeUnit.SECONDS, fakeClock.getDeadlineTicker())),
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);

    call.start(callListener, new Metadata());

    fakeClock.forwardNanos(TimeUnit.SECONDS.toNanos(1) + 1);

    verify(stream, times(1)).cancel(statusCaptor.capture());
    assertEquals(Status.Code.DEADLINE_EXCEEDED, statusCaptor.getValue().getCode());
    assertThat(statusCaptor.getValue().getDescription())
        .matches("CallOptions deadline exceeded after [0-9]+\\.[0-9]+s. "
            + "Name resolution delay 0.000000000 seconds. \\[remote_addr=127\\.0\\.0\\.1:443\\]");
  }

  @Test
  public void expiredDeadlineCancelsStream_Context() {
    fakeClock.forwardTime(System.nanoTime(), TimeUnit.NANOSECONDS);

    Context context = Context.current()
        .withDeadlineAfter(1, TimeUnit.SECONDS, deadlineCancellationExecutor);
    Context origContext = context.attach();

    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);

    context.detach(origContext);

    call.start(callListener, new Metadata());

    fakeClock.forwardNanos(TimeUnit.SECONDS.toNanos(1) + 1);

    verify(stream, times(1)).cancel(statusCaptor.capture());
    assertEquals(Status.Code.DEADLINE_EXCEEDED, statusCaptor.getValue().getCode());
    assertThat(statusCaptor.getValue().getDescription())
        .matches("Context deadline exceeded after [0-9]+\\.[0-9]+s. "
            + "Name resolution delay 0.000000000 seconds. \\[remote_addr=127\\.0\\.0\\.1:443\\]");
  }

  @Test
  public void cancelWithoutStart() {
    fakeClock.forwardTime(System.nanoTime(), TimeUnit.NANOSECONDS);

    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        baseCallOptions.withDeadline(Deadline.after(1, TimeUnit.SECONDS)),
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);
    // Nothing happens as a result, but it shouldn't throw
    call.cancel("canceled", null);
  }

  @Test
  public void streamCancelAbortsDeadlineTimer() {
    fakeClock.forwardTime(System.nanoTime(), TimeUnit.NANOSECONDS);

    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        baseCallOptions.withDeadline(Deadline.after(1, TimeUnit.SECONDS)),
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);
    call.start(callListener, new Metadata());
    call.cancel("canceled", null);

    // Run the deadline timer, which should have been cancelled by the previous call to cancel()
    fakeClock.forwardNanos(TimeUnit.SECONDS.toNanos(1) + 1);

    verify(stream, times(1)).cancel(statusCaptor.capture());

    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
  }

  /**
   * Without a context or call options deadline,
   * a timeout should not be set in metadata.
   */
  @Test
  public void timeoutShouldNotBeSet() {
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);

    Metadata headers = new Metadata();

    call.start(callListener, headers);

    assertFalse(headers.containsKey(GrpcUtil.TIMEOUT_KEY));
  }

  @Test
  public void cancelInOnMessageShouldInvokeStreamCancel() throws Exception {
    final ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);
    final Exception cause = new Exception();
    ClientCall.Listener<Void> callListener =
        new ClientCall.Listener<Void>() {
          @Override
          public void onMessage(Void message) {
            call.cancel("foo", cause);
          }
        };

    call.start(callListener, new Metadata());
    call.halfClose();
    call.request(1);

    verify(stream).start(listenerArgumentCaptor.capture());
    ClientStreamListener streamListener = listenerArgumentCaptor.getValue();
    streamListener.onReady();
    streamListener.headersRead(new Metadata());
    streamListener
        .messagesAvailable(new SingleMessageProducer(new ByteArrayInputStream(new byte[0])));
    verify(stream).cancel(statusCaptor.capture());
    Status status = statusCaptor.getValue();
    assertEquals(Status.CANCELLED.getCode(), status.getCode());
    assertEquals("foo", status.getDescription());
    assertSame(cause, status.getCause());
  }

  @Test
  public void halfClosedShouldNotBeReady() {
    when(stream.isReady()).thenReturn(true);
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        MoreExecutors.directExecutor(),
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);

    call.start(callListener, new Metadata());
    assertThat(call.isReady()).isTrue();

    call.halfClose();
    assertThat(call.isReady()).isFalse();
  }

  @Test
  public void startAddsMaxSize() {
    CallOptions callOptions =
        baseCallOptions.withMaxInboundMessageSize(1).withMaxOutboundMessageSize(2);
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        new SerializingExecutor(Executors.newSingleThreadExecutor()),
        callOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector)
            .setDecompressorRegistry(decompressorRegistry);

    call.start(callListener, new Metadata());

    verify(stream).setMaxInboundMessageSize(1);
    verify(stream).setMaxOutboundMessageSize(2);
  }

  @Test
  public void getAttributes() {
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method, MoreExecutors.directExecutor(), baseCallOptions, clientStreamProvider,
        deadlineCancellationExecutor, channelCallTracer, configSelector);
    Attributes attrs = Attributes.newBuilder().set(
        Attributes.Key.<String>create("fake key"), "fake value").build();
    when(stream.getAttributes()).thenReturn(attrs);

    assertNotEquals(attrs, call.getAttributes());

    call.start(callListener, new Metadata());

    assertEquals(attrs, call.getAttributes());
  }

  @Test
  public void onCloseExceptionCaughtAndLogged() {
    DelayedExecutor executor = new DelayedExecutor();
    ClientCallImpl<Void, Void> call = new ClientCallImpl<>(
        method,
        executor,
        baseCallOptions,
        clientStreamProvider,
        deadlineCancellationExecutor,
        channelCallTracer, configSelector);

    call.start(callListener, new Metadata());
    verify(stream).start(listenerArgumentCaptor.capture());
    final ClientStreamListener streamListener = listenerArgumentCaptor.getValue();
    streamListener.headersRead(new Metadata());

    doThrow(new RuntimeException("Exception thrown by onClose() in ClientCall")).when(callListener)
        .onClose(any(Status.class), any(Metadata.class));

    Status status = Status.RESOURCE_EXHAUSTED.withDescription("simulated");
    streamListener.closed(status, PROCESSED, new Metadata());
    executor.release();

    verify(callListener).onClose(same(status), any(Metadata.class));
  }

  private static final class DelayedExecutor implements Executor {
    private final BlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();

    @Override
    public void execute(Runnable command) {
      commands.add(command);
    }

    void release() {
      while (!commands.isEmpty()) {
        commands.poll().run();
      }
    }
  }
}
