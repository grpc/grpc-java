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
import static org.mockito.Mockito.verify;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.grpc.internal.ServerCallImpl.ServerStreamListenerImpl;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;

@RunWith(Parameterized.class)
public class ServerCallsImplClientSendsOneTest extends ServerCallImplAbstractTest {

  public ServerCallsImplClientSendsOneTest(MethodType type) {
    super(type);
  }

  @Override
  protected boolean shouldRunTest(MethodType type) {
    return type.clientSendsOneMessage();
  }

  @Test
  public void streamListener_messageRead_failsOnMultiple() {
    ServerStreamListenerImpl<Long> streamListener =
        new ServerCallImpl.ServerStreamListenerImpl<Long>(call, callListener, context);
    streamListener.messageRead(method.streamRequest(1234L));
    streamListener.messageRead(method.streamRequest(1234L));

    // Makes sure this was only called once.
    verify(callListener).onMessage(1234L);

    verify(stream).close(statusCaptor.capture(), Mockito.isA(Metadata.class));
    assertEquals(Status.Code.INTERNAL, statusCaptor.getValue().getCode());
  }
}
