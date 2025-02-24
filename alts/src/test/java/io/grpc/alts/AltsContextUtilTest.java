/*
 * Copyright 2018 The gRPC Authors
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


package io.grpc.alts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.ClientCall;
import io.grpc.ServerCall;
import io.grpc.alts.AltsContext.SecurityLevel;
import io.grpc.alts.internal.AltsInternalContext;
import io.grpc.alts.internal.AltsProtocolNegotiator;
import io.grpc.alts.internal.HandshakerResult;
import io.grpc.alts.internal.Identity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AltsContextUtil}. */
@RunWith(JUnit4.class)
public class AltsContextUtilTest {
  @Test
  public void check_noAttributeValue() {
    assertFalse(AltsContextUtil.check(Attributes.newBuilder().build()));
  }

  @Test
  public void check_unexpectedAttributeValueType() {
    assertFalse(AltsContextUtil.check(Attributes.newBuilder()
        .set(AltsProtocolNegotiator.AUTH_CONTEXT_KEY, new Object())
        .build()));
  }

  @Test
  public void check_altsInternalContext() {
    assertTrue(AltsContextUtil.check(Attributes.newBuilder()
        .set(AltsProtocolNegotiator.AUTH_CONTEXT_KEY, AltsInternalContext.getDefaultInstance())
        .build()));
  }

  @Test
  public void checkServer_altsInternalContext() {
    ServerCall<?,?> call = mock(ServerCall.class);
    when(call.getAttributes()).thenReturn(Attributes.newBuilder()
        .set(AltsProtocolNegotiator.AUTH_CONTEXT_KEY, AltsInternalContext.getDefaultInstance())
        .build());

    assertTrue(AltsContextUtil.check(call));
  }

  @Test
  public void checkClient_altsInternalContext() {
    ClientCall<?,?> call = mock(ClientCall.class);
    when(call.getAttributes()).thenReturn(Attributes.newBuilder()
        .set(AltsProtocolNegotiator.AUTH_CONTEXT_KEY, AltsInternalContext.getDefaultInstance())
        .build());

    assertTrue(AltsContextUtil.check(call));
  }

  @Test
  public void createFrom_altsInternalContext() {
    HandshakerResult handshakerResult =
        HandshakerResult.newBuilder()
            .setPeerIdentity(Identity.newBuilder().setServiceAccount("remote@peer"))
            .setLocalIdentity(Identity.newBuilder().setServiceAccount("local@peer"))
            .build();

    AltsContext context = AltsContextUtil.createFrom(Attributes.newBuilder()
        .set(AltsProtocolNegotiator.AUTH_CONTEXT_KEY, new AltsInternalContext(handshakerResult))
        .build());
    assertEquals("remote@peer", context.getPeerServiceAccount());
    assertEquals("local@peer", context.getLocalServiceAccount());
    assertEquals(SecurityLevel.INTEGRITY_AND_PRIVACY, context.getSecurityLevel());
  }

  @Test(expected = IllegalArgumentException.class)
  public void createFrom_noAttributeValue() {
    AltsContextUtil.createFrom(Attributes.newBuilder().build());
  }

  @Test
  public void createFromServer_altsInternalContext() {
    HandshakerResult handshakerResult =
        HandshakerResult.newBuilder()
            .setPeerIdentity(Identity.newBuilder().setServiceAccount("remote@peer"))
            .setLocalIdentity(Identity.newBuilder().setServiceAccount("local@peer"))
            .build();

    ServerCall<?,?> call = mock(ServerCall.class);
    when(call.getAttributes()).thenReturn(Attributes.newBuilder()
        .set(AltsProtocolNegotiator.AUTH_CONTEXT_KEY, new AltsInternalContext(handshakerResult))
        .build());

    AltsContext context = AltsContextUtil.createFrom(call);
    assertEquals("remote@peer", context.getPeerServiceAccount());
  }

  @Test
  public void createFromClient_altsInternalContext() {
    HandshakerResult handshakerResult =
        HandshakerResult.newBuilder()
            .setPeerIdentity(Identity.newBuilder().setServiceAccount("remote@peer"))
            .setLocalIdentity(Identity.newBuilder().setServiceAccount("local@peer"))
            .build();

    ClientCall<?,?> call = mock(ClientCall.class);
    when(call.getAttributes()).thenReturn(Attributes.newBuilder()
        .set(AltsProtocolNegotiator.AUTH_CONTEXT_KEY, new AltsInternalContext(handshakerResult))
        .build());

    AltsContext context = AltsContextUtil.createFrom(call);
    assertEquals("remote@peer", context.getPeerServiceAccount());
  }
}
