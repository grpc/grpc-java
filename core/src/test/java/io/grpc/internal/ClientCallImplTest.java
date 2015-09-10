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

import static io.grpc.internal.GrpcUtil.TIMER_SERVICE;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.IntegerMarshaller;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StringMarshaller;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link io.grpc.internal.ClientCallImpl}.
 */
@RunWith(JUnit4.class)
public class ClientCallImplTest {

  private MethodDescriptor<String, Integer> method = MethodDescriptor.create(
      MethodDescriptor.MethodType.UNKNOWN, "/service/method",
      new StringMarshaller(), new IntegerMarshaller());
  private ExecutorService executor = Executors.newSingleThreadExecutor();

  @Mock
  private ClientTransport mockTransport;

  @Mock
  private ClientStream mockStream;

  @Captor
  private ArgumentCaptor<ClientStreamListener> listener;

  private ClientCallImpl<String, Integer> clientCall;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    clientCall = new ClientCallImpl<String, Integer>(method, new SerializingExecutor(executor),
        CallOptions.DEFAULT, new ClientCallImpl.ClientTransportProvider() {
          @Override
          public ClientTransport get() {
            return mockTransport;
          }
        },
        SharedResourceHolder.get(TIMER_SERVICE));
    when(mockTransport.newStream(any(MethodDescriptor.class), any(Metadata.class),
        listener.capture())).thenReturn(mockStream);
  }

  @Test
  public void testOnReadyBatchesFlush() throws Exception {
    final CountDownLatch latch = new CountDownLatch(1);
    clientCall.start(new ClientCall.Listener<Integer>() {
      @Override
      public void onReady() {
        clientCall.sendMessage("a");
        clientCall.sendMessage("b");
        clientCall.sendMessage("c");
        latch.countDown();
      }
    }, new Metadata());

    listener.getValue().onReady();

    latch.await(5, TimeUnit.SECONDS);

    verify(mockStream, times(3)).writeMessage(any(InputStream.class));
    verify(mockStream, times(1)).flush();
  }

  @Test
  public void testOutsideOnReadyDoesNotBatchFlush() {
    clientCall.start(new ClientCall.Listener<Integer>() {
    }, new Metadata());

    clientCall.sendMessage("a");
    clientCall.sendMessage("b");
    clientCall.sendMessage("c");

    verify(mockStream, times(3)).writeMessage(any(InputStream.class));
    verify(mockStream, times(3)).flush();
  }
}
