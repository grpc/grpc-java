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

import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.Codec;
import io.grpc.Metadata;
import io.grpc.Status;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
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
    inOrder.verify(realStream).start(listener);
  }

  @Test
  public void setStream_sendsAllMessages() {
    stream.start(listener);
    stream.setCompressor(Codec.Identity.NONE);
    stream.setDecompressor(Codec.Identity.NONE);

    stream.setMessageCompression(true);
    InputStream message = new ByteArrayInputStream(new byte[]{'a'});
    stream.writeMessage(message);
    stream.setMessageCompression(false);
    stream.writeMessage(message);

    stream.setStream(realStream);

    verify(realStream).setCompressor(Codec.Identity.NONE);
    verify(realStream).setDecompressor(Codec.Identity.NONE);

    // Verify that the order was correct, even though they should be interleaved with the
    // writeMessage calls
    verify(realStream).setMessageCompression(true);
    verify(realStream, times(2)).setMessageCompression(false);

    verify(realStream, times(2)).writeMessage(message);
    verify(realStream).start(listener);
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
  }

  @Test
  public void setStream_flowControl() {
    stream.start(listener);
    stream.request(1);
    stream.request(2);

    stream.setStream(realStream);

    verify(realStream).request(3);
  }

  @Test
  public void startThenCancelled() {
    stream.start(listener);
    stream.cancel(Status.CANCELLED);
    verify(listener).closed(eq(Status.CANCELLED), isA(Metadata.class));
  }

  @Test
  public void startThenSetStreamThenCancelled() {
    stream.start(listener);
    stream.setStream(realStream);
    stream.cancel(Status.CANCELLED);
    verify(realStream).start(same(listener));
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
    stream.cancel(Status.CANCELLED);
    verify(realStream).cancel(same(Status.CANCELLED));
  }

  @Test
  public void cancelledThenStart() {
    stream.cancel(Status.CANCELLED);
    stream.start(listener);
    verify(listener).closed(eq(Status.CANCELLED), isA(Metadata.class));
  }
}
