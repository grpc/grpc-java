/*
 * Copyright 2014, Google Inc. All rights reserved.
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

package io.grpc.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.io.ByteStreams;
import com.google.common.primitives.Bytes;

import io.grpc.transport.MessageDeframer.Listener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * Tests for {@link MessageDeframer}.
 */
@RunWith(JUnit4.class)
public class MessageDeframerTest {
  @Mock
  private Listener listener;

  private MessageDeframer deframer;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    deframer = new MessageDeframer(listener);
  }

  @Test
  public void simplePayload() {
    deframer.request(1);
    deframer.deframe(buffer(new byte[] {0, 0, 0, 0, 2, 3, 14}), false);
    verify(listener, times(1)).messagesAvailable(deframer);
    assertEquals(Bytes.asList(new byte[]{3, 14}), bytes().get(0));
    verify(listener, atLeastOnce()).bytesRead(anyInt());
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void multipleMessagesInFrameNotifiesListenerOnce() {
    deframer.request(2);
    deframer.deframe(buffer(new byte[] {0, 0, 0, 0, 1, 3, 0, 0, 0, 0, 2, 14, 15}), false);
    verify(listener, times(1)).messagesAvailable(deframer);
    List<List<Byte>> messages = bytes();
    assertEquals(Bytes.asList(new byte[] {3}), messages.get(0));
    verify(listener, atLeastOnce()).bytesRead(anyInt());
    assertEquals(Bytes.asList(new byte[] {14, 15}), messages.get(1));
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void endOfStreamWithPayloadShouldNotifyEndOfStream() {
    deframer.request(1);
    deframer.deframe(buffer(new byte[] {0, 0, 0, 0, 1, 3}), true);
    verify(listener, times(1)).messagesAvailable(deframer);
    assertEquals(Bytes.asList(new byte[] {3}), bytes().get(0));
    verify(listener).endOfStream();
    verify(listener, atLeastOnce()).bytesRead(anyInt());
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void endOfStreamShouldNotifyEndOfStream() {
    deframer.deframe(buffer(new byte[0]), true);
    verify(listener).endOfStream();
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void payloadSplitBetweenBuffers() {
    deframer.request(1);
    deframer.deframe(buffer(new byte[]{0, 0, 0, 0, 7, 3, 14, 1, 5, 9}), false);
    verify(listener).bytesRead(10);
    deframer.deframe(buffer(new byte[] {2, 6}), false);
    verify(listener, times(1)).messagesAvailable(deframer);
    assertEquals(Bytes.asList(new byte[] {3, 14, 1, 5, 9, 2, 6}), bytes().get(0));
    verify(listener).bytesRead(2);
    verify(listener).deliveryStalled();
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void frameHeaderSplitBetweenBuffers() {
    deframer.request(1);

    deframer.deframe(buffer(new byte[]{0, 0}), false);
    verify(listener, atLeastOnce()).bytesRead(2);
    deframer.deframe(buffer(new byte[] {0, 0, 1, 3}), false);
    verify(listener, times(1)).messagesAvailable(deframer);
    assertEquals(Bytes.asList(new byte[] {3}), bytes().get(0));
    verify(listener, atLeastOnce()).bytesRead(anyInt());
    verify(listener).deliveryStalled();
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void emptyPayload() {
    deframer.request(1);
    deframer.deframe(buffer(new byte[] {0, 0, 0, 0, 0}), false);
    verify(listener, times(1)).messagesAvailable(deframer);
    assertEquals(Bytes.asList(), bytes().get(0));
    verify(listener, atLeastOnce()).bytesRead(anyInt());
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void largerFrameSize() {
    deframer.request(1);
    deframer.deframe(ReadableBuffers.wrap(
        Bytes.concat(new byte[] {0, 0, 0, 3, (byte) 232}, new byte[1000])), false);
    verify(listener, times(1)).messagesAvailable(deframer);
    assertEquals(Bytes.asList(new byte[1000]), bytes().get(0));
    verify(listener, atLeastOnce()).bytesRead(anyInt());
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void endOfStreamCallbackShouldWaitForMessageDelivery() {
    deframer.deframe(buffer(new byte[]{0, 0, 0, 0, 1, 3}), true);
    deframer.request(1);
    verify(listener, times(1)).messagesAvailable(deframer);
    assertEquals(Bytes.asList(new byte[] {3}), bytes().get(0));
    verify(listener).endOfStream();
    verify(listener, atLeastOnce()).bytesRead(anyInt());
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void deframeReturnsFlowControlImmediatelyIfRequestsVisibleToTransport() {
    deframer.request(1);
    deframer.deframe(buffer(new byte[] {0, 0, 0, 0}), false);
    verify(listener, times(1)).bytesRead(4);
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void deframeReturnsFlowControlViaApplicationIfRequestCountNotVisibleToTransport() {
    deframer.deframe(buffer(new byte[] {0, 0, 0, 0}), false);
    deframer.deframe(buffer(new byte[] {7, 3, 14, 1, 5, 9}), false);
    // Transport returns bytes via the application thread but there are no requested messages
    // so nothing is returned yet
    verify(listener, times(2)).messagesAvailable(deframer);
    verifyNoMoreInteractions(listener);
    reset(listener);
    assertEquals(0, bytes().size());

    // Trigger notification and return of flow control
    deframer.request(1);
    assertEquals(0, bytes().size());
    verify(listener, times(1)).messagesAvailable(deframer);
    // Bytes are returned in one call
    verify(listener, times(1)).bytesRead(10);
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void compressed() {
    deframer = new MessageDeframer(listener, MessageDeframer.Compression.GZIP);
    deframer.request(1);

    byte[] payload = compress(new byte[1000]);
    assertTrue(payload.length < 100);
    byte[] header = new byte[] {1, 0, 0, 0, (byte) payload.length};
    deframer.deframe(buffer(Bytes.concat(header, payload)), false);
    verify(listener, times(1)).messagesAvailable(deframer);
    assertEquals(Bytes.asList(new byte[1000]), bytes().get(0));
    verify(listener, atLeastOnce()).bytesRead(anyInt());
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void requestIsReentrantSafe() {
    final AtomicInteger depth = new AtomicInteger();
    doAnswer(new Answer() {
      @Override
      public Object answer(InvocationOnMock invocation) throws Throwable {
        assertEquals(3, deframer.drainTo(new StreamListener.MessageConsumer() {
          @Override
          public void accept(InputStream message) throws Exception {
            assertEquals(0, depth.getAndIncrement());
            deframer.request(1);
            depth.decrementAndGet();
          }
        }));
        return null;
      }
    }).when(listener).messagesAvailable(deframer);
    deframer.request(1);
    deframer.deframe(buffer(new byte[]{0, 0, 0, 0, 1, 3, 0, 0, 0, 0, 1, 3, 0, 0, 0, 0, 1, 3}),
        true);
    verify(listener, times(1)).messagesAvailable(deframer);
    verify(listener).endOfStream();
    verify(listener, atLeastOnce()).bytesRead(anyInt());
    verifyNoMoreInteractions(listener);
  }

  private List<List<Byte>> bytes() {
    final List<List<Byte>> result = new ArrayList<List<Byte>>();
    deframer.drainTo(new StreamListener.MessageConsumer() {
      @Override
      public void accept(InputStream message) {
        try {
          ArrayList<Byte> next = new ArrayList<Byte>();
          next.addAll(Bytes.asList(ByteStreams.toByteArray(message)));
          result.add(next);
        } catch (IOException ioe) {
          throw new RuntimeException(ioe);
        }
      }
    });
    return result;
  }

  private static ReadableBuffer buffer(byte[] bytes) {
    return ReadableBuffers.wrap(bytes);
  }

  private static byte[] compress(byte[] bytes) {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      GZIPOutputStream zip = new GZIPOutputStream(baos);
      zip.write(bytes);
      zip.close();
      return baos.toByteArray();
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }
}
