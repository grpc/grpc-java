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

  private final ServerCall<?,?> call = mock(ServerCall.class);

  @Test
  public void check_noAttributeValue() {
    when(call.getAttributes()).thenReturn(Attributes.newBuilder().build());

    assertFalse(AltsContextUtil.check(call));
  }

  @Test
  public void contains_unexpectedAttributeValueType() {
    when(call.getAttributes()).thenReturn(Attributes.newBuilder()
        .set(AltsProtocolNegotiator.AUTH_CONTEXT_KEY, new Object())
        .build());

    assertFalse(AltsContextUtil.check(call));
  }

  @Test
  public void contains_altsInternalContext() {
    when(call.getAttributes()).thenReturn(Attributes.newBuilder()
        .set(AltsProtocolNegotiator.AUTH_CONTEXT_KEY, AltsInternalContext.getDefaultInstance())
        .build());

    assertTrue(AltsContextUtil.check(call));
  }

  @Test
  public void from_altsInternalContext() {
    HandshakerResult handshakerResult =
        HandshakerResult.newBuilder()
            .setPeerIdentity(Identity.newBuilder().setServiceAccount("remote@peer"))
            .setLocalIdentity(Identity.newBuilder().setServiceAccount("local@peer"))
            .build();
    when(call.getAttributes()).thenReturn(Attributes.newBuilder()
        .set(AltsProtocolNegotiator.AUTH_CONTEXT_KEY,  new AltsInternalContext(handshakerResult))
        .build());

    AltsContext context = AltsContextUtil.createFrom(call);
    assertEquals("remote@peer", context.getPeerServiceAccount());
    assertEquals("local@peer", context.getLocalServiceAccount());
    assertEquals(SecurityLevel.INTEGRITY_AND_PRIVACY, context.getSecurityLevel());
  }

  @Test(expected = IllegalArgumentException.class)
  public void from_noAttributeValue() {
    when(call.getAttributes()).thenReturn(Attributes.newBuilder().build());

    AltsContextUtil.createFrom(call);
  }
}
