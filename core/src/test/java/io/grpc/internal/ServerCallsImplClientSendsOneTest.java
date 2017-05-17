/*
 * Copyright 2017, Google Inc. All rights reserved.
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
import static org.mockito.Mockito.verify;

import com.google.common.collect.Collections2;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.grpc.internal.ServerCallImpl.ServerStreamListenerImpl;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.Mockito;

@RunWith(Parameterized.class)
public class ServerCallsImplClientSendsOneTest extends ServerCallImplAbstractTest {

  /**
   * Unit tests for UNARY and SERVER_STREAMING methods.
   */
  @Parameters
  public static Collection<Object[]> params() {
    return Collections2.transform(
        TestUtils.Methods.CLIENT_SENDS_ONE,
        TestUtils.Methods.TO_PARAM_FN
    );
  }

  public ServerCallsImplClientSendsOneTest(MethodType type) {
    super(type);
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
