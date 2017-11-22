/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.Compressor;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.grpc.StringMarshaller;
import java.io.InputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Unit tests for {@link RetriableStream}.
 */
@RunWith(JUnit4.class)
public class RetriableStreamTest {
  private static final String CANCELLED_BECAUSE_COMMITTED =
      "Stream thrown away because RetriableStream committed";
  private static final String AUTHORITY = "fakeAuthority";
  private static final Compressor COMPRESSOR = mock(Compressor.class);
  private static final DecompressorRegistry DECOMPRESSOR_REGISTRY =
      DecompressorRegistry.getDefaultInstance();
  private static final int MAX_INBOUND_MESSAGE_SIZE = 1234;
  private static final int MAX_OUTNBOUND_MESSAGE_SIZE = 5678;

  private final MethodDescriptor<String, String> method =
      MethodDescriptor.<String, String>newBuilder().setType(MethodType.BIDI_STREAMING)
          .setFullMethodName(MethodDescriptor.generateFullMethodName("service_foo", "method_bar"))
          .setRequestMarshaller(new StringMarshaller())
          .setResponseMarshaller(new StringMarshaller()).build();
  private final ClientStreamListener masterListener = mock(ClientStreamListener.class);

  // TODO(zdapeng): for mockito 2.7+: instead of spy(an_impl),
  // use mock(RetriableStream.class, withSettings().useConstructorArgs(method))
  private final RetriableStream<String> retriableStream = spy(new RetriableStream<String>(method) {
    @Override
    void postCommit() {
    }

    @Override
    ClientStream newSubstream() {
      return null;
    }

    @Override
    Status prestart() {
      return null;
    }
  });

  private void setShouldRetry(boolean shouldRetry) {
    doReturn(shouldRetry).when(retriableStream).shouldRetry();
  }

  private void setNextSubstream(ClientStream clientStream) {
    doReturn(clientStream).when(retriableStream).newSubstream();
  }


  @Test
  public void retry_everythingDrained() {
    ClientStream mockStream1 = mock(ClientStream.class);
    setNextSubstream(mockStream1);
    InOrder inOrder = inOrder(retriableStream, masterListener, mockStream1);

    // stream settings before start
    retriableStream.setAuthority(AUTHORITY);
    retriableStream.setCompressor(COMPRESSOR);
    retriableStream.setDecompressorRegistry(DECOMPRESSOR_REGISTRY);
    retriableStream.setFullStreamDecompression(false);
    retriableStream.setFullStreamDecompression(true);
    retriableStream.setMaxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
    retriableStream.setMessageCompression(true);
    retriableStream.setMaxOutboundMessageSize(MAX_OUTNBOUND_MESSAGE_SIZE);
    retriableStream.setMessageCompression(false);

    inOrder.verifyNoMoreInteractions();

    // start
    retriableStream.start(masterListener);

    inOrder.verify(retriableStream).prestart();
    inOrder.verify(retriableStream).newSubstream();

    inOrder.verify(mockStream1).setAuthority(AUTHORITY);
    inOrder.verify(mockStream1).setCompressor(COMPRESSOR);
    inOrder.verify(mockStream1).setDecompressorRegistry(DECOMPRESSOR_REGISTRY);
    inOrder.verify(mockStream1).setFullStreamDecompression(false);
    inOrder.verify(mockStream1).setFullStreamDecompression(true);
    inOrder.verify(mockStream1).setMaxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream1).setMessageCompression(true);
    inOrder.verify(mockStream1).setMaxOutboundMessageSize(MAX_OUTNBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream1).setMessageCompression(false);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verifyNoMoreInteractions();

    retriableStream.sendMessage("msg1");
    retriableStream.sendMessage("msg2");
    retriableStream.request(345);
    retriableStream.flush();
    retriableStream.flush();
    retriableStream.sendMessage("msg3");
    retriableStream.request(456);

    inOrder.verify(mockStream1, times(2)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream1).request(345);
    inOrder.verify(mockStream1, times(2)).flush();
    inOrder.verify(mockStream1).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream1).request(456);
    inOrder.verifyNoMoreInteractions();

    // retry1
    ClientStream mockStream2 = mock(ClientStream.class);
    setNextSubstream(mockStream2);
    inOrder = inOrder(retriableStream, masterListener, mockStream1, mockStream2);
    setShouldRetry(true);
    sublistenerCaptor1.getValue().closed(Status.UNAVAILABLE, new Metadata());

    inOrder.verify(retriableStream).shouldRetry();
    // TODO(zdapeng): send more messages dureing backoff, then forward backoff ticker
    inOrder.verify(retriableStream).newSubstream();
    inOrder.verify(mockStream2).setAuthority(AUTHORITY);
    inOrder.verify(mockStream2).setCompressor(COMPRESSOR);
    inOrder.verify(mockStream2).setDecompressorRegistry(DECOMPRESSOR_REGISTRY);
    inOrder.verify(mockStream2).setFullStreamDecompression(false);
    inOrder.verify(mockStream2).setFullStreamDecompression(true);
    inOrder.verify(mockStream2).setMaxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream2).setMessageCompression(true);
    inOrder.verify(mockStream2).setMaxOutboundMessageSize(MAX_OUTNBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream2).setMessageCompression(false);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2, times(2)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream2).request(345);
    inOrder.verify(mockStream2, times(2)).flush();
    inOrder.verify(mockStream2).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream2).request(456);
    inOrder.verifyNoMoreInteractions();

    // send more messages
    retriableStream.sendMessage("msg1 after retry1");
    retriableStream.sendMessage("msg2 after retry1");

    // retry2
    ClientStream mockStream3 = mock(ClientStream.class);
    setNextSubstream(mockStream3);
    inOrder = inOrder(retriableStream, masterListener, mockStream1, mockStream2, mockStream3);
    setShouldRetry(true);
    sublistenerCaptor2.getValue().closed(Status.UNAVAILABLE, new Metadata());

    inOrder.verify(retriableStream).shouldRetry();
    // TODO(zdapeng): send more messages dureing backoff, then forward backoff ticker
    inOrder.verify(retriableStream).newSubstream();
    inOrder.verify(mockStream3).setAuthority(AUTHORITY);
    inOrder.verify(mockStream3).setCompressor(COMPRESSOR);
    inOrder.verify(mockStream3).setDecompressorRegistry(DECOMPRESSOR_REGISTRY);
    inOrder.verify(mockStream3).setFullStreamDecompression(false);
    inOrder.verify(mockStream3).setFullStreamDecompression(true);
    inOrder.verify(mockStream3).setMaxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream3).setMessageCompression(true);
    inOrder.verify(mockStream3).setMaxOutboundMessageSize(MAX_OUTNBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream3).setMessageCompression(false);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream3).start(sublistenerCaptor3.capture());
    inOrder.verify(mockStream3, times(2)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream3).request(345);
    inOrder.verify(mockStream3, times(2)).flush();
    inOrder.verify(mockStream3).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream3).request(456);
    inOrder.verify(mockStream3, times(2)).writeMessage(any(InputStream.class));
    inOrder.verifyNoMoreInteractions();


    // no more retry
    setShouldRetry(false);
    sublistenerCaptor3.getValue().closed(Status.UNAVAILABLE, new Metadata());

    inOrder.verify(retriableStream).shouldRetry();
    inOrder.verify(retriableStream).postCommit();
    inOrder.verify(masterListener).closed(any(Status.class), any(Metadata.class));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void headersRead_cancel() {
    ClientStream mockStream1 = mock(ClientStream.class);
    setNextSubstream(mockStream1);
    InOrder inOrder = inOrder(retriableStream);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    sublistenerCaptor1.getValue().headersRead(new Metadata());

    inOrder.verify(retriableStream).postCommit();

    retriableStream.cancel(Status.CANCELLED);

    inOrder.verify(retriableStream, never()).postCommit();
  }

  @Test
  public void retry_headersRead_cancel() {
    ClientStream mockStream1 = mock(ClientStream.class);
    setNextSubstream(mockStream1);
    InOrder inOrder = inOrder(retriableStream);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    // retry
    // TODO(zdapeng): forward backoff ticker
    setShouldRetry(true);
    ClientStream mockStream2 = mock(ClientStream.class);
    setNextSubstream(mockStream2);
    sublistenerCaptor1.getValue().closed(Status.UNAVAILABLE, new Metadata());

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(retriableStream, never()).postCommit();

    // headersRead
    sublistenerCaptor2.getValue().headersRead(new Metadata());

    inOrder.verify(retriableStream).postCommit();

    // cancel
    retriableStream.cancel(Status.CANCELLED);

    inOrder.verify(retriableStream, never()).postCommit();
  }

  @Test
  public void headersRead_closed() {
    ClientStream mockStream1 = mock(ClientStream.class);
    setNextSubstream(mockStream1);
    InOrder inOrder = inOrder(retriableStream);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    sublistenerCaptor1.getValue().headersRead(new Metadata());

    inOrder.verify(retriableStream).postCommit();

    Status status = Status.UNAVAILABLE;
    Metadata metadata = new Metadata();
    sublistenerCaptor1.getValue().closed(status, metadata);

    inOrder.verify(retriableStream, never()).postCommit();
    verify(masterListener).closed(status, metadata);
  }

  @Test
  public void retry_headersRead_closed() {
    ClientStream mockStream1 = mock(ClientStream.class);
    setNextSubstream(mockStream1);
    InOrder inOrder = inOrder(retriableStream);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    // retry
    // TODO(zdapeng): forward backoff ticker
    setShouldRetry(true);
    ClientStream mockStream2 = mock(ClientStream.class);
    setNextSubstream(mockStream2);
    sublistenerCaptor1.getValue().closed(Status.UNAVAILABLE, new Metadata());

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(retriableStream, never()).postCommit();

    // headersRead
    sublistenerCaptor2.getValue().headersRead(new Metadata());

    inOrder.verify(retriableStream).postCommit();

    // closed
    setShouldRetry(false);
    Status status = Status.UNAVAILABLE;
    Metadata metadata = new Metadata();
    sublistenerCaptor2.getValue().closed(status, metadata);

    inOrder.verify(retriableStream, never()).postCommit();
    verify(masterListener).closed(status, metadata);
  }

  @Test
  public void cancel_closed() {
    ClientStream mockStream1 = mock(ClientStream.class);
    setNextSubstream(mockStream1);
    InOrder inOrder = inOrder(retriableStream);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    // cancel
    retriableStream.cancel(Status.CANCELLED);

    inOrder.verify(retriableStream).postCommit();
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockStream1).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());

    // closed
    setShouldRetry(false);
    Status status = Status.UNAVAILABLE;
    Metadata metadata = new Metadata();
    sublistenerCaptor1.getValue().closed(status, metadata);
    inOrder.verify(retriableStream, never()).postCommit();
  }

  @Test
  public void retry_cancel_closed() {
    ClientStream mockStream1 = mock(ClientStream.class);
    setNextSubstream(mockStream1);
    InOrder inOrder = inOrder(retriableStream);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    // retry
    // TODO(zdapeng): forward backoff ticker
    setShouldRetry(true);
    ClientStream mockStream2 = mock(ClientStream.class);
    setNextSubstream(mockStream2);
    sublistenerCaptor1.getValue().closed(Status.UNAVAILABLE, new Metadata());

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(retriableStream, never()).postCommit();

    // cancel
    retriableStream.cancel(Status.CANCELLED);

    inOrder.verify(retriableStream).postCommit();
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockStream2).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());

    // closed
    setShouldRetry(false);
    Status status = Status.UNAVAILABLE;
    Metadata metadata = new Metadata();
    sublistenerCaptor2.getValue().closed(status, metadata);
    inOrder.verify(retriableStream, never()).postCommit();
  }

  @Test
  public void unretriableClosed_cancel() {
    ClientStream mockStream1 = mock(ClientStream.class);
    setNextSubstream(mockStream1);
    InOrder inOrder = inOrder(retriableStream);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    // closed
    Status status = Status.UNAVAILABLE;
    Metadata metadata = new Metadata();
    sublistenerCaptor1.getValue().closed(status, metadata);

    inOrder.verify(retriableStream).postCommit();
    verify(masterListener).closed(status, metadata);

    // cancel
    retriableStream.cancel(Status.CANCELLED);
    inOrder.verify(retriableStream, never()).postCommit();
  }

  @Test
  public void retry_unretriableClosed_cancel() {
    ClientStream mockStream1 = mock(ClientStream.class);
    setNextSubstream(mockStream1);
    InOrder inOrder = inOrder(retriableStream);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    // retry
    // TODO(zdapeng): forward backoff ticker
    setShouldRetry(true);
    ClientStream mockStream2 = mock(ClientStream.class);
    setNextSubstream(mockStream2);
    sublistenerCaptor1.getValue().closed(Status.UNAVAILABLE, new Metadata());

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(retriableStream, never()).postCommit();

    // closed
    setShouldRetry(false);
    Status status = Status.UNAVAILABLE;
    Metadata metadata = new Metadata();
    sublistenerCaptor2.getValue().closed(status, metadata);

    inOrder.verify(retriableStream).postCommit();
    verify(masterListener).closed(status, metadata);

    // cancel
    retriableStream.cancel(Status.CANCELLED);
    inOrder.verify(retriableStream, never()).postCommit();
  }

  @Test
  public void retry_cancelWhileBackoff() {
    // TODO(zdapeng)
  }


  @Test
  public void operationsWhileDraining() {
    final ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    final ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    final Status cancelStatus = Status.CANCELLED.withDescription("c");
    ClientStream mockStream1 = spy(new NoopClientStream() {
      @Override
      public void request(int numMessages) {
        retriableStream.sendMessage("substream1 request " + numMessages);
        if (numMessages > 1) {
          retriableStream.request(--numMessages);
        }
      }
    });

    ClientStream mockStream2 = spy(new NoopClientStream() {
      @Override
      public void request(int numMessages) {
        retriableStream.sendMessage("substream2 request " + numMessages);

        if (numMessages == 3) {
          verify(this).start(sublistenerCaptor2.capture());
          sublistenerCaptor2.getValue().headersRead(new Metadata());
        }
        if (numMessages == 2) {
          retriableStream.request(100);
        }
        if (numMessages == 100) {
          retriableStream.cancel(cancelStatus);
        }
      }
    });

    InOrder inOrder = inOrder(retriableStream, mockStream1, mockStream2);

    setNextSubstream(mockStream1);
    retriableStream.start(masterListener);

    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());

    retriableStream.request(3);

    inOrder.verify(mockStream1).request(3);
    inOrder.verify(mockStream1).writeMessage(any(InputStream.class)); // msg "substream1 request 3"
    inOrder.verify(mockStream1).request(2);
    inOrder.verify(mockStream1).writeMessage(any(InputStream.class)); // msg "substream1 request 2"
    inOrder.verify(mockStream1).request(1);
    inOrder.verify(mockStream1).writeMessage(any(InputStream.class)); // msg "substream1 request 1"

    // retry
    // TODO(zdapeng): send more messages dureing backoff, then forward backoff ticker
    setShouldRetry(true);
    setNextSubstream(mockStream2);
    sublistenerCaptor1.getValue().closed(Status.UNAVAILABLE, new Metadata());

    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());

    inOrder.verify(mockStream2).request(3);
    inOrder.verify(retriableStream).postCommit();
    inOrder.verify(mockStream2).writeMessage(any(InputStream.class)); // msg "substream1 request 3"
    inOrder.verify(mockStream2).request(2);
    inOrder.verify(mockStream2).writeMessage(any(InputStream.class)); // msg "substream1 request 2"
    inOrder.verify(mockStream2).request(1);

    // msg "substream1 request 1"
    // msg "substream2 request 3"
    // msg "substream2 request 2"
    inOrder.verify(mockStream2, times(3)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream2).request(100);

    verify(mockStream2).cancel(cancelStatus);

    // "substream2 request 1" will never be sent
    inOrder.verify(mockStream2, never()).writeMessage(any(InputStream.class));
  }
}
