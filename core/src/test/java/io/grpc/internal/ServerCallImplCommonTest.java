/*
 * Copyright 2015, Google Inc. All rights reserved.
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.grpc.internal.ServerCallImpl.ServerStreamListenerImpl;
import java.io.InputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ServerCallImplCommonTest extends ServerCallImplAbstractTest {

  public ServerCallImplCommonTest(MethodType type) {
    super(type);
  }

  @Override
  protected boolean shouldRunTest(MethodType type) {
    return true;
  }

  @Test
  public void request() {
    call.request(10);

    verify(stream).request(10);
  }

  @Test
  public void sendHeader_firstCall() {
    Metadata headers = new Metadata();

    call.sendHeaders(headers);

    verify(stream).writeHeaders(headers);
  }

  @Test
  public void sendHeader_failsOnSecondCall() {
    call.sendHeaders(new Metadata());
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("sendHeaders has already been called");

    call.sendHeaders(new Metadata());
  }

  @Test
  public void sendHeader_failsOnClosed() {
    call.close(Status.CANCELLED, new Metadata());

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("call is closed");

    call.sendHeaders(new Metadata());
  }

  @Test
  public void sendMessage() {
    call.sendHeaders(new Metadata());
    call.sendMessage(1234L);

    verify(stream).writeMessage(isA(InputStream.class));
    verify(stream).flush();
  }

  @Test
  public void sendMessage_failsOnClosed() {
    call.sendHeaders(new Metadata());
    call.close(Status.CANCELLED, new Metadata());

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("call is closed");

    call.sendMessage(1234L);
  }

  @Test
  public void sendMessage_failsIfheadersUnsent() {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("sendHeaders has not been called");

    call.sendMessage(1234L);
  }

  @Test
  public void sendMessage_closesOnFailure() {
    call.sendHeaders(new Metadata());
    doThrow(new RuntimeException("bad")).when(stream).writeMessage(isA(InputStream.class));

    try {
      call.sendMessage(1234L);
      fail();
    } catch (RuntimeException e) {
      // expected
    }

    verify(stream).close(isA(Status.class), isA(Metadata.class));
  }

  @Test
  public void isReady() {
    when(stream.isReady()).thenReturn(true);

    assertTrue(call.isReady());
  }

  @Test
  public void getAuthority() {
    when(stream.getAuthority()).thenReturn("fooapi.googleapis.com");
    assertEquals("fooapi.googleapis.com", call.getAuthority());
    verify(stream).getAuthority();
  }

  @Test
  public void getNullAuthority() {
    when(stream.getAuthority()).thenReturn(null);
    assertNull(call.getAuthority());
    verify(stream).getAuthority();
  }

  @Test
  public void setMessageCompression() {
    call.setMessageCompression(true);

    verify(stream).setMessageCompression(true);
  }

  @Test
  public void streamListener_halfClosed() {
    ServerStreamListenerImpl<Long> streamListener =
        new ServerCallImpl.ServerStreamListenerImpl<Long>(call, callListener, context);

    streamListener.halfClosed();

    verify(callListener).onHalfClose();
  }

  @Test
  public void streamListener_halfClosed_onlyOnce() {
    ServerStreamListenerImpl<Long> streamListener =
        new ServerCallImpl.ServerStreamListenerImpl<Long>(call, callListener, context);
    streamListener.halfClosed();
    // canceling the call should short circuit future halfClosed() calls.
    streamListener.closed(Status.CANCELLED);

    streamListener.halfClosed();

    verify(callListener).onHalfClose();
  }

  @Test
  public void streamListener_closedOk() {
    ServerStreamListenerImpl<Long> streamListener =
        new ServerCallImpl.ServerStreamListenerImpl<Long>(call, callListener, context);

    streamListener.closed(Status.OK);

    verify(callListener).onComplete();
    assertTrue(context.isCancelled());
    assertNull(context.cancellationCause());
  }

  @Test
  public void streamListener_closedCancelled() {
    ServerStreamListenerImpl<Long> streamListener =
        new ServerCallImpl.ServerStreamListenerImpl<Long>(call, callListener, context);

    streamListener.closed(Status.CANCELLED);

    verify(callListener).onCancel();
    assertTrue(context.isCancelled());
    assertNull(context.cancellationCause());
  }

  @Test
  public void streamListener_onReady() {
    ServerStreamListenerImpl<Long> streamListener =
        new ServerCallImpl.ServerStreamListenerImpl<Long>(call, callListener, context);

    streamListener.onReady();

    verify(callListener).onReady();
  }

  @Test
  public void streamListener_onReady_onlyOnce() {
    ServerStreamListenerImpl<Long> streamListener =
        new ServerCallImpl.ServerStreamListenerImpl<Long>(call, callListener, context);
    streamListener.onReady();
    // canceling the call should short circuit future halfClosed() calls.
    streamListener.closed(Status.CANCELLED);

    streamListener.onReady();

    verify(callListener).onReady();
  }

  @Test
  public void streamListener_messageRead() {
    ServerStreamListenerImpl<Long> streamListener =
        new ServerCallImpl.ServerStreamListenerImpl<Long>(call, callListener, context);
    streamListener.messageRead(method.streamRequest(1234L));

    verify(callListener).onMessage(1234L);
  }

  @Test
  public void streamListener_messageRead_onlyOnce() {
    ServerStreamListenerImpl<Long> streamListener =
        new ServerCallImpl.ServerStreamListenerImpl<Long>(call, callListener, context);
    streamListener.messageRead(method.streamRequest(1234L));
    // canceling the call should short circuit future halfClosed() calls.
    streamListener.closed(Status.CANCELLED);

    streamListener.messageRead(method.streamRequest(1234L));

    verify(callListener).onMessage(1234L);
  }

  @Test
  public void streamListener_unexpectedRuntimeException() {
    ServerStreamListenerImpl<Long> streamListener =
        new ServerCallImpl.ServerStreamListenerImpl<Long>(call, callListener, context);
    doThrow(new RuntimeException("unexpected exception"))
        .when(callListener)
        .onMessage(any(Long.class));

    InputStream inputStream = method.streamRequest(1234L);

    thrown.expect(RuntimeException.class);
    thrown.expectMessage("unexpected exception");
    streamListener.messageRead(inputStream);
  }
}
