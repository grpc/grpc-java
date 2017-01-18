/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Codec;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.NoopClientStream;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Tests for {@link DelayedStream}.  Most of the state checking is enforced by
 * {@link ClientCallImpl} so we don't check it here.
 */
@RunWith(JUnit4.class)
public class DelayedStreamTest {
  @Rule public final ExpectedException thrown = ExpectedException.none();

  @Mock private ClientStreamListener listener;
  @Mock private ClientStream realStream;
  @Captor private ArgumentCaptor<ClientStreamListener> listenerCaptor;
  @Captor private ArgumentCaptor<Status> statusCaptor;
  private DelayedStream stream = new DelayedStream();

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void setStream_setAuthority() {
    final String authority = "becauseIsaidSo";
    stream.setAuthority(authority);
    stream.start(listener);
    stream.setStream(realStream);
    InOrder inOrder = inOrder(realStream);
    inOrder.verify(realStream).setAuthority(authority);
    inOrder.verify(realStream).start(any(ClientStreamListener.class));
  }

  @Test(expected = IllegalStateException.class)
  public void setAuthority_afterStart() {
    stream.start(listener);
    stream.setAuthority("notgonnawork");
  }

  @Test(expected = IllegalStateException.class)
  public void start_afterStart() {
    stream.start(listener);
    stream.start(mock(ClientStreamListener.class));
  }

  @Test(expected = IllegalStateException.class)
  public void setDecompressor_beforeSetStream() {
    stream.start(listener);
    stream.setDecompressor(Codec.Identity.NONE);
  }

  @Test
  public void setStream_sendsAllMessages() {
    stream.start(listener);
    stream.setMaxInboundMessageSize(9897);
    stream.setMaxOutboundMessageSize(5586);
    stream.setCompressor(Codec.Identity.NONE);

    stream.setMessageCompression(true);
    InputStream message = new ByteArrayInputStream(new byte[]{'a'});
    stream.writeMessage(message);
    stream.setMessageCompression(false);
    stream.writeMessage(message);

    stream.setStream(realStream);
    stream.setDecompressor(Codec.Identity.NONE);

    verify(realStream).setMaxInboundMessageSize(9897);
    verify(realStream).setMaxOutboundMessageSize(5586);
    verify(realStream).setCompressor(Codec.Identity.NONE);
    verify(realStream).setDecompressor(Codec.Identity.NONE);

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
    stream.setStream(realStream);

    verify(realStream).halfClose();
  }

  @Test
  public void setStream_flush() {
    stream.start(listener);
    stream.flush();
    stream.setStream(realStream);
    verify(realStream).flush();

    stream.flush();
    verify(realStream, times(2)).flush();
  }

  @Test
  public void setStream_flowControl() {
    stream.start(listener);
    stream.request(1);
    stream.request(2);
    stream.setStream(realStream);
    verify(realStream).request(1);
    verify(realStream).request(2);

    stream.request(3);
    verify(realStream).request(3);
  }

  @Test
  public void setStream_setMessageCompression() {
    stream.start(listener);
    stream.setMessageCompression(false);
    stream.setStream(realStream);
    verify(realStream).setMessageCompression(false);

    stream.setMessageCompression(true);
    verify(realStream).setMessageCompression(true);
  }

  @Test
  public void setStream_isReady() {
    stream.start(listener);
    assertFalse(stream.isReady());
    stream.setStream(realStream);
    verify(realStream, never()).isReady();

    assertFalse(stream.isReady());
    verify(realStream).isReady();

    when(realStream.isReady()).thenReturn(true);
    assertTrue(stream.isReady());
    verify(realStream, times(2)).isReady();
  }

  @Test
  public void startCancelThenSetStream() {
    stream.start(listener);
    Status status = Status.CANCELLED.withDescription("Yes do it");
    stream.cancel(status);
    verify(listener).closed(same(status), any(Metadata.class));
    stream.setStream(realStream);
    verify(realStream).start(same(DelayedStream.NOOP_STREAM_LISTENER));
    verify(realStream).cancel(status);
  }

  @Test
  public void startThenSetStreamThenCancelled() {
    stream.start(listener);
    stream.setStream(realStream);
    stream.cancel(Status.CANCELLED);
    verify(realStream).start(any(ClientStreamListener.class));
    verify(realStream).cancel(same(Status.CANCELLED));
  }

  @Test
  public void setStreamThenStartThenCancelled() {
    stream.setStream(realStream);
    stream.start(listener);
    stream.cancel(Status.CANCELLED);
    verify(realStream).start(same(listener));
    verify(realStream).cancel(same(Status.CANCELLED));
  }

  @Test
  public void setStreamThenCancelled() {
    stream.setStream(realStream);
    try {
      stream.cancel(Status.CANCELLED);
      fail("Should have thrown");
    } catch (IllegalStateException e) {
      assertEquals("cancel() must be called after start()", e.getMessage());
    }
    stream.start(listener);
    verify(realStream).start(same(listener));
    verifyNoMoreInteractions(realStream);
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void cancelThenSetStream() {
    try {
      stream.cancel(Status.CANCELLED);
      fail("Should have thrown");
    } catch (IllegalStateException e) {
      assertEquals("cancel() must be called after start()", e.getMessage());
    }
    stream.setStream(realStream);
    stream.start(listener);
    stream.isReady();
    verify(realStream).start(same(listener));
    verify(realStream).isReady();
    verifyNoMoreInteractions(realStream);
  }

  @Test
  public void setStreamThensetMaxMessageSizes() {
    InOrder inOrder = inOrder(realStream);
    stream.setStream(realStream);
    stream.setMaxInboundMessageSize(9897);
    inOrder.verify(realStream).setMaxInboundMessageSize(9897);
    stream.setMaxOutboundMessageSize(5586);
    inOrder.verify(realStream).setMaxOutboundMessageSize(5586);
    stream.start(listener);
    inOrder.verify(realStream).start(same(listener));
  }

  @Test
  public void setStreamTwice() {
    stream.start(listener);
    stream.setStream(realStream);
    verify(realStream).start(any(ClientStreamListener.class));
    ClientStream realStream2 = mock(ClientStream.class);
    InOrder inOrder = inOrder(realStream2);
    try {
      stream.setStream(realStream2);
      fail("Should have thrown");
    } catch (IllegalStateException e) {
      assertEquals("DelayedStream.setStream() is called more than once", e.getMessage());
    }
    inOrder.verify(realStream2).start(same(DelayedStream.NOOP_STREAM_LISTENER));
    inOrder.verify(realStream2).cancel(statusCaptor.capture());
    assertEquals(Status.Code.CANCELLED, statusCaptor.getValue().getCode());
    stream.flush();
    verify(realStream).flush();
    verifyNoMoreInteractions(realStream);
  }

  @Test
  public void cancel_beforeStart() {
    Status status = Status.CANCELLED.withDescription("that was quick");
    try {
      stream.cancel(status);
      fail("Should have thrown");
    } catch (IllegalStateException e) {
      assertEquals("cancel() must be called after start()", e.getMessage());
    }
    stream.start(listener);
    verifyNoMoreInteractions(listener);
    stream.setStream(realStream);
    verify(realStream).start(any(ClientStreamListener.class));
    verifyNoMoreInteractions(realStream);
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
    stream.setStream(new NoopClientStream() {
      @Override
      public void start(ClientStreamListener listener) {
        // This call to the listener should end up being delayed.
        listener.onReady();
      }

      @Override
      public boolean isReady() {
        return true;
      }
    });
    assertTrue(isReadyListener.onReadyCalled);
  }

  @Test
  public void listener_allQueued() {
    final Metadata headers = new Metadata();
    final InputStream message1 = mock(InputStream.class);
    final InputStream message2 = mock(InputStream.class);
    final Metadata trailers = new Metadata();
    final Status status = Status.UNKNOWN.withDescription("unique status");

    final InOrder inOrder = inOrder(listener);
    stream.start(listener);
    stream.setStream(new NoopClientStream() {
      @Override
      public void start(ClientStreamListener passedListener) {
        passedListener.onReady();
        passedListener.headersRead(headers);
        passedListener.messageRead(message1);
        passedListener.onReady();
        passedListener.messageRead(message2);
        passedListener.closed(status, trailers);

        verifyNoMoreInteractions(listener);
      }
    });
    inOrder.verify(listener).onReady();
    inOrder.verify(listener).headersRead(headers);
    inOrder.verify(listener).messageRead(message1);
    inOrder.verify(listener).onReady();
    inOrder.verify(listener).messageRead(message2);
    inOrder.verify(listener).closed(status, trailers);
  }

  @Test
  public void listener_noQueued() {
    final Metadata headers = new Metadata();
    final InputStream message = mock(InputStream.class);
    final Metadata trailers = new Metadata();
    final Status status = Status.UNKNOWN.withDescription("unique status");

    stream.start(listener);
    stream.setStream(realStream);
    verify(realStream).start(listenerCaptor.capture());
    ClientStreamListener delayedListener = listenerCaptor.getValue();
    delayedListener.onReady();
    verify(listener).onReady();
    delayedListener.headersRead(headers);
    verify(listener).headersRead(headers);
    delayedListener.messageRead(message);
    verify(listener).messageRead(message);
    delayedListener.closed(status, trailers);
    verify(listener).closed(status, trailers);
  }

}
