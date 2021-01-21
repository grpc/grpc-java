/*
 * Copyright 2019 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.Lists;
import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.alts.internal.HandshakerResult;
import io.grpc.alts.internal.Identity;
import io.grpc.alts.internal.TsiHandshakeHandler;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AuthorizationUtil}. */
@RunWith(JUnit4.class)
public final class AuthorizationUtilTest {

  @Test
  public void altsAuthorizationCheckWithoutAuthContext() throws Exception {
    Status status =
        AuthorizationUtil.clientAuthorizationCheck(
            fakeServerCallWithAuthContext(null), Lists.newArrayList("Alice"));
    assertThat(status.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(status.getDescription()).startsWith("Peer ALTS AuthContext not found");
  }

  @Test
  public void altsAuthorizationCheckAllowed() throws Exception {
    Status status =
        AuthorizationUtil.clientAuthorizationCheck(
            fakeServerCallForPeerServiceAccount("Alice"), Lists.newArrayList("Alice", "Bob"));
    assertThat(status.getCode()).isEqualTo(Status.Code.OK);
  }

  @Test
  public void altsAuthorizationCheckDenied() throws Exception {
    Status status =
        AuthorizationUtil.clientAuthorizationCheck(
            fakeServerCallForPeerServiceAccount("Alice"), Lists.newArrayList("Bob", "Joe"));
    assertThat(status.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(status.getDescription()).endsWith("not authorized");
  }

  @Test
  public void altsAuthorizationCheckNoAltsAuthContext() throws Exception {
    Status status =
        AuthorizationUtil.clientAuthorizationCheck(
            fakeServerCallWithAuthContext(new Object()), Lists.newArrayList("Alice"));
    assertThat(status.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(status.getDescription()).startsWith("Peer ALTS AuthContext not found");
  }

  static FakeServerCall fakeServerCallForPeerServiceAccount(String peerServiceAccount) {
    HandshakerResult handshakerResult =
              HandshakerResult.newBuilder()
              .setPeerIdentity(Identity.newBuilder().setServiceAccount(peerServiceAccount))
              .build();
    return fakeServerCallWithAuthContext(new AltsAuthContext(handshakerResult));
  }

  static FakeServerCall fakeServerCallWithAuthContext(@Nullable Object authContext) {
    Attributes.Builder attrsBuilder = Attributes.newBuilder();
    if (authContext != null) {
      attrsBuilder.set(TsiHandshakeHandler.AUTH_CONTEXT_KEY, authContext);
    }
    return new FakeServerCall(attrsBuilder.build());
  }

  private static class FakeServerCall extends ServerCall<String, String> {
    final Attributes attrs;

    FakeServerCall(Attributes attrs) {
      this.attrs = attrs;
    }

    @Override
    public void request(int numMessages) {
      throw new AssertionError("Should not be called");
    }

    @Override
    public void sendHeaders(Metadata headers) {
      throw new AssertionError("Should not be called");
    }

    @Override
    public void sendMessage(String message) {
      throw new AssertionError("Should not be called");
    }

    @Override
    public void close(Status status, Metadata trailers) {
      throw new AssertionError("Should not be called");
    }

    @Override
    public boolean isCancelled() {
      throw new AssertionError("Should not be called");
    }

    @Override
    public Attributes getAttributes() {
      return attrs;
    }

    @Override
    public String getAuthority() {
      throw new AssertionError("Should not be called");
    }

    @Override
    public MethodDescriptor<String, String> getMethodDescriptor() {
      throw new AssertionError("Should not be called");
    }
  }
}
