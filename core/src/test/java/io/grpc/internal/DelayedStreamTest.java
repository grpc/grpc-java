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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Codec;
import io.grpc.Compressor;
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.ClientStreamListener.RpcProgress;
import io.grpc.internal.SingleMessageProducer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/**
 * Tests for {@link DelayedStream}.  Most of the state checking is enforced by
 * {@link ClientCallImpl} so we don't check it here.
 */
@RunWith(JUnit4.class)
public class DelayedStreamTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private ClientStreamListener listener;
  @Mock private ClientStream realStream;
  @Captor private ArgumentCaptor<ClientStreamListener> listenerCaptor;
  private DelayedStream stream = new DelayedStream();

  @Test
  public void setStream_setAuthority() {
    final String authority = "becauseIsaidSo";
    stream.setAuthority(authority);
    stream.start(listener);
    callMeMaybe(stream.setStream(realStream));
    InOrder inOrder = inOrder(realStream);
    inOrder.verify(realStream).setAuthority(authority);
    inOrder.verify(realStream).start(any(ClientStreamListener.class));
  }

  @Test(expected = IllegalStateException.class)
  public void start_afterStart() {
    stream.start(listener);
    stream.start(mock(ClientStreamListener.class));
  }

  @Test(expected = IllegalStateException.class)
  public void writeMessage_beforeStart() {
    InputStream message = new ByteArrayInputStream(new byte[]{'a'});
    stream.writeMessage(message);
  }

  @Test(expected = IllegalStateException.class)
  public void flush_beforeStart() {
    stream.flush();
  }

  @Test(expected = IllegalStateException.class)
  public void request_beforeStart() {
    stream.request(1);
  }

  @Test(expected = IllegalStateException.class)
  public void halfClose_beforeStart() {
    stream.halfClose();
  }

  @Test
  public void setStream_sendsAllMessages() {
    stream.setCompressor(Codec.Identity.NONE);
    stream.setDecompressorRegistry(DecompressorRegistry.getDefaultInstance());
    stream.start(listener);

    stream.setMessageCompression(true);
    InputStream message = new ByteArrayInputStream(new byte[]{'a'});
    stream.writeMessage(message);
    stream.setMessageCompression(false);
    stream.writeMessage(message);

    callMeMaybe(stream.setStream(realStream));

    verify(realStream).setCompressor(Codec.Identity.NONE);
    verify(realStream).setDecompressorRegistry(DecompressorRegistry.getDefaultInstance());

    verify(realStream).setMessageCompression(true);
    verify(realStream).setMessageCompression(false);

    verify(realStream, times(2)).writeMessage(message);
    verify(realStream).start(listenerCaptor.capture());

    stream.writeMessage(message);
    verify(realStream, times(3)).writeMessage(message);

    verifyNoMoreInteractions(listener);
    listenerCaptor.getValue().onReady();
    verify(listener).onReady();
  }

  @Test
  public void setStream_halfClose() {
    stream.start(listener);
    stream.halfClose();
    callMeMaybe(stream.setStream(realStream));

    verify(realStream).halfClose();
  }

  @Test
  public void setStream_flush() {
    stream.start(listener);
    stream.flush();
    callMeMaybe(stream.setStream(realStream));
    verify(realStream).flush();

    stream.flush();
    verify(realStream, times(2)).flush();
  }

  @Test
  public void setStream_flowControl() {
    stream.start(listener);
    stream.request(1);
    stream.request(2);
    callMeMaybe(stream.setStream(realStream));
    verify(realStream).request(1);
    verify(realStream).request(2);

    stream.request(3);
    verify(realStream).request(3);
  }

  @Test
  public void setStreamThenStart() {
    stream.optimizeForDirectExecutor();
    stream.setCompressor(mock(Compressor.class));
    stream.setFullStreamDecompression(false);
    stream.setDecompressorRegistry(DecompressorRegistry.emptyInstance());
    stream.setDeadline(Deadline.after(1, TimeUnit.MINUTES));
    stream.setAuthority("auth");
    stream.setMaxInboundMessageSize(10);
    stream.setMaxOutboundMessageSize(10);

    assertNull(stream.setStream(realStream));
    stream.start(listener);
    stream.request(1);

    InOrder inOrder = inOrder(realStream);
    inOrder.verify(realStream).optimizeForDirectExecutor();
    inOrder.verify(realStream).setCompressor(any(Compressor.class));
    inOrder.verify(realStream).setFullStreamDecompression(false);
    inOrder.verify(realStream).setDecompressorRegistry(any(DecompressorRegistry.class));
    inOrder.verify(realStream).setDeadline(any(Deadline.class));
    inOrder.verify(realStream).setAuthority("auth");
    inOrder.verify(realStream).setMaxInboundMessageSize(10);
    inOrder.verify(realStream).setMaxOutboundMessageSize(10);
    verify(realStream).request(1);
    verify(realStream).start(same(listener));
    verifyNoMoreInteractions(realStream);
  }

  @Test
  public void startThenSetRealStream() {
    stream.setAuthority("auth");
    stream.optimizeForDirectExecutor();
    stream.setMaxInboundMessageSize(10);
    stream.setCompressor(mock(Compressor.class));
    stream.setFullStreamDecompression(false);
    stream.setDeadline(Deadline.after(1, TimeUnit.MINUTES));
    stream.setMaxOutboundMessageSize(10);
    stream.setDecompressorRegistry(DecompressorRegistry.emptyInstance());
    stream.start(listener);
    stream.request(1);
    InputStream message = mock(InputStream.class);
    stream.writeMessage(message);
    Runnable runnable = stream.setStream(realStream);
    assertNotNull(runnable);
    callMeMaybe(runnable);
    stream.getAttributes();
    stream.request(4);

    InOrder inOrder = inOrder(realStream);
    inOrder.verify(realStream).setAuthority("auth");
    inOrder.verify(realStream).optimizeForDirectExecutor();
    inOrder.verify(realStream).setMaxInboundMessageSize(10);
    inOrder.verify(realStream).setCompressor(any(Compressor.class));
    inOrder.verify(realStream).setFullStreamDecompression(false);
    inOrder.verify(realStream).setDeadline(any(Deadline.class));
    inOrder.verify(realStream).setMaxOutboundMessageSize(10);
    inOrder.verify(realStream).setDecompressorRegistry(any(DecompressorRegistry.class));
    inOrder.verify(realStream).start(listenerCaptor.capture());
    inOrder.verify(realStream).request(1);
    inOrder.verify(realStream).writeMessage(same(message));
    inOrder.verify(realStream).getAttributes();
    verify(realStream).request(4);
    verifyNoMoreInteractions(realStream);
    ClientStreamListener delayedListener = listenerCaptor.getValue();
    delayedListener.onReady();
    verify(listener).onReady();
  }

  @Test
  public void drainPendingCallRacesCancel() {
    stream.start(listener);
    InputStream message = mock(InputStream.class);
    stream.writeMessage(message);
    stream.flush();
    Runnable runnable = stream.setStream(realStream);
    assertNotNull(runnable);
    stream.cancel(Status.CANCELLED);
    callMeMaybe(runnable);

    InOrder inOrder = inOrder(realStream);
    inOrder.verify(realStream).start(any(ClientStreamListener.class));
    inOrder.verify(realStream).writeMessage(same(message));
    inOrder.verify(realStream).flush();
    inOrder.verify(realStream).cancel(Status.CANCELLED);
    verifyNoMoreInteractions(realStream);
  }

  @Test
  public void setStream_setMessageCompression() {
    stream.start(listener);
    stream.setMessageCompression(false);
    callMeMaybe(stream.setStream(realStream));
    verify(realStream).setMessageCompression(false);

    stream.setMessageCompression(true);
    verify(realStream).setMessageCompression(true);
  }

  @Test
  public void setStream_isReady() {
    stream.start(listener);
    assertFalse(stream.isReady());
    callMeMaybe(stream.setStream(realStream));
    verify(realStream, never()).isReady();

    assertFalse(stream.isReady());
    verify(realStream).isReady();

    when(realStream.isReady()).thenReturn(true);
    assertTrue(stream.isReady());
    verify(realStream, times(2)).isReady();
  }

  @Test
  public void setStream_getAttributes() {
    Attributes attributes =
        Attributes.newBuilder().set(Attributes.Key.<String>create("fakeKey"), "fakeValue").build();
    when(realStream.getAttributes()).thenReturn(attributes);

    stream.start(listener);

    assertEquals(Attributes.EMPTY, stream.getAttributes());

    callMeMaybe(stream.setStream(realStream));
    assertEquals(attributes, stream.getAttributes());
  }

  @Test
  public void startThenCancelled() {
    stream.start(listener);
    stream.cancel(Status.CANCELLED);
    verify(listener).closed(eq(Status.CANCELLED), any(RpcProgress.class), any(Metadata.class));
  }

  @Test
  public void startThenSetStreamThenCancelled() {
    stream.start(listener);
    callMeMaybe(stream.setStream(realStream));
    stream.cancel(Status.CANCELLED);
    verify(realStream).start(any(ClientStreamListener.class));
    verify(realStream).cancel(same(Status.CANCELLED));
  }

  @Test
  public void setStreamThenStartThenCancelled() {
    callMeMaybe(stream.setStream(realStream));
    stream.start(listener);
    stream.cancel(Status.CANCELLED);
    verify(realStream).start(same(listener));
    verify(realStream).cancel(same(Status.CANCELLED));
  }

  @Test
  public void setStreamTwice() {
    stream.start(listener);
    callMeMaybe(stream.setStream(realStream));
    verify(realStream).start(any(ClientStreamListener.class));
    callMeMaybe(stream.setStream(mock(ClientStream.class)));
    stream.flush();
    verify(realStream).flush();
  }

  @Test
  public void cancelThenSetStream() {
    stream.start(listener);
    stream.cancel(Status.CANCELLED);
    callMeMaybe(stream.setStream(realStream));
    stream.isReady();
    verifyNoMoreInteractions(realStream);
  }

  @Test(expected = IllegalStateException.class)
  public void cancel_beforeStart() {
    Status status = Status.CANCELLED.withDescription("that was quick");
    stream.cancel(status);
  }

  @Test
  public void listener_onReadyDelayedUntilPassthrough() {
    class IsReadyListener extends NoopClientStreamListener {
      boolean onReadyCalled;

      @Override
      public void onReady() {
        // If onReady was not delayed, then passthrough==false and isReady will return false.
        assertTrue(stream.isReady());
        onReadyCalled = true;
      }
    }

    IsReadyListener isReadyListener = new IsReadyListener();
    stream.start(isReadyListener);
    callMeMaybe(stream.setStream(new NoopClientStream() {
      @Override
      public void start(ClientStreamListener listener) {
        // This call to the listener should end up being delayed.
        listener.onReady();
      }

      @Override
      public boolean isReady() {
        return true;
      }
    }));
    assertTrue(isReadyListener.onReadyCalled);
  }

  @Test
  public void listener_allQueued() {
    final Metadata headers = new Metadata();
    final InputStream message1 = mock(InputStream.class);
    final InputStream message2 = mock(InputStream.class);
    final SingleMessageProducer producer1 = new SingleMessageProducer(message1);
    final SingleMessageProducer producer2 = new SingleMessageProducer(message2);
    final Metadata trailers = new Metadata();
    final Status status = Status.UNKNOWN.withDescription("unique status");

    final InOrder inOrder = inOrder(listener);
    stream.start(listener);
    callMeMaybe(stream.setStream(new NoopClientStream() {
      @Override
      public void start(ClientStreamListener passedListener) {
        passedListener.onReady();
        passedListener.headersRead(headers);
        passedListener.messagesAvailable(producer1);
        passedListener.onReady();
        passedListener.messagesAvailable(producer2);
        passedListener.closed(status, RpcProgress.PROCESSED, trailers);

        verifyNoMoreInteractions(listener);
      }
    }));
    inOrder.verify(listener).onReady();
    inOrder.verify(listener).headersRead(headers);
    inOrder.verify(listener).messagesAvailable(producer1);
    inOrder.verify(listener).onReady();
    inOrder.verify(listener).messagesAvailable(producer2);
    inOrder.verify(listener).closed(status, RpcProgress.PROCESSED, trailers);
  }

  @Test
  public void listener_noQueued() {
    final Metadata headers = new Metadata();
    final InputStream message = mock(InputStream.class);
    final SingleMessageProducer producer = new SingleMessageProducer(message);
    final Metadata trailers = new Metadata();
    final Status status = Status.UNKNOWN.withDescription("unique status");

    stream.start(listener);
    callMeMaybe(stream.setStream(realStream));
    verify(realStream).start(listenerCaptor.capture());
    ClientStreamListener delayedListener = listenerCaptor.getValue();
    delayedListener.onReady();
    verify(listener).onReady();
    delayedListener.headersRead(headers);
    verify(listener).headersRead(headers);
    delayedListener.messagesAvailable(producer);
    verify(listener).messagesAvailable(producer);
    delayedListener.closed(status, RpcProgress.PROCESSED, trailers);
    verify(listener).closed(status, RpcProgress.PROCESSED, trailers);
  }

  @Test
  public void appendTimeoutInsight_notStarted() {
    InsightBuilder insight = new InsightBuilder();
    stream.appendTimeoutInsight(insight);
    assertThat(insight.toString()).isEqualTo("[]");
  }

  @Test
  public void appendTimeoutInsight_realStreamNotSet() {
    InsightBuilder insight = new InsightBuilder();
    stream.start(listener);
    stream.appendTimeoutInsight(insight);
    assertThat(insight.toString()).matches("\\[buffered_nanos=[0-9]+\\, waiting_for_connection]");
  }

  @Test
  public void appendTimeoutInsight_realStreamSet() {
    doAnswer(new Answer<Void>() {
        @Override
        public Void answer(InvocationOnMock in) {
          InsightBuilder insight = (InsightBuilder) in.getArguments()[0];
          insight.appendKeyValue("remote_addr", "127.0.0.1:443");
          return null;
        }
      }).when(realStream).appendTimeoutInsight(any(InsightBuilder.class));
    stream.start(listener);
    callMeMaybe(stream.setStream(realStream));

    InsightBuilder insight = new InsightBuilder();
    stream.appendTimeoutInsight(insight);
    assertThat(insight.toString())
        .matches("\\[buffered_nanos=[0-9]+, remote_addr=127\\.0\\.0\\.1:443\\]");
  }

  private void callMeMaybe(Runnable r) {
    if (r != null) {
      r.run();
    }
  }
}
