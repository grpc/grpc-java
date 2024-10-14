/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.s2a.internal.handshaker;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.truth.Expect;
import io.grpc.s2a.internal.handshaker.S2AIdentity;
import io.netty.handler.ssl.OpenSslSessionContext;
import io.netty.handler.ssl.SslContext;
import java.security.GeneralSecurityException;
import java.util.Optional;
import javax.net.ssl.SSLSessionContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SslContextFactory}. */
@RunWith(JUnit4.class)
public final class SslContextFactoryTest {
  @Rule public final Expect expect = Expect.create();
  private static final String FAKE_TARGET_NAME = "fake_target_name";
  private S2AStub stub;
  private FakeWriter writer;

  @Before
  public void setUp() {
    writer = new FakeWriter();
    stub = S2AStub.newInstanceForTesting(writer);
    writer.setReader(stub.getReader());
  }

  @Test
  public void createForClient_returnsValidSslContext() throws Exception {
    SslContext sslContext =
        SslContextFactory.createForClient(
            stub, FAKE_TARGET_NAME, /* localIdentity= */ Optional.empty());

    expect.that(sslContext).isNotNull();
    expect.that(sslContext.sessionCacheSize()).isEqualTo(1);
    expect.that(sslContext.sessionTimeout()).isEqualTo(300);
    expect.that(sslContext.isClient()).isTrue();
    expect.that(sslContext.applicationProtocolNegotiator().protocols()).containsExactly("h2");
    SSLSessionContext sslSessionContext = sslContext.sessionContext();
    if (sslSessionContext instanceof OpenSslSessionContext) {
      OpenSslSessionContext openSslSessionContext = (OpenSslSessionContext) sslSessionContext;
      expect.that(openSslSessionContext.isSessionCacheEnabled()).isFalse();
    }
  }

  @Test
  public void createForClient_withLocalIdentity_returnsValidSslContext() throws Exception {
    SslContext sslContext =
        SslContextFactory.createForClient(
            stub, FAKE_TARGET_NAME, Optional.of(S2AIdentity.fromSpiffeId("fake-spiffe-id")));

    expect.that(sslContext).isNotNull();
    expect.that(sslContext.sessionCacheSize()).isEqualTo(1);
    expect.that(sslContext.sessionTimeout()).isEqualTo(300);
    expect.that(sslContext.isClient()).isTrue();
    expect.that(sslContext.applicationProtocolNegotiator().protocols()).containsExactly("h2");
    SSLSessionContext sslSessionContext = sslContext.sessionContext();
    if (sslSessionContext instanceof OpenSslSessionContext) {
      OpenSslSessionContext openSslSessionContext = (OpenSslSessionContext) sslSessionContext;
      expect.that(openSslSessionContext.isSessionCacheEnabled()).isFalse();
    }
  }

  @Test
  public void createForClient_returnsEmptyResponse_error() throws Exception {
    writer.setBehavior(FakeWriter.Behavior.EMPTY_RESPONSE);

    S2AConnectionException expected =
        assertThrows(
            S2AConnectionException.class,
            () ->
                SslContextFactory.createForClient(
                    stub, FAKE_TARGET_NAME, /* localIdentity= */ Optional.empty()));

    assertThat(expected)
        .hasMessageThat()
        .contains("Response from S2A server does NOT contain ClientTlsConfiguration.");
  }

  @Test
  public void createForClient_returnsErrorStatus_error() throws Exception {
    writer.setBehavior(FakeWriter.Behavior.ERROR_STATUS);

    S2AConnectionException expected =
        assertThrows(
            S2AConnectionException.class,
            () ->
                SslContextFactory.createForClient(
                    stub, FAKE_TARGET_NAME, /* localIdentity= */ Optional.empty()));

    assertThat(expected).hasMessageThat().contains("Intended ERROR Status from FakeWriter.");
  }

  @Test
  public void createForClient_getsErrorFromServer_throwsError() throws Exception {
    writer.sendIoError();

    GeneralSecurityException expected =
        assertThrows(
            GeneralSecurityException.class,
            () ->
                SslContextFactory.createForClient(
                    stub, FAKE_TARGET_NAME, /* localIdentity= */ Optional.empty()));

    assertThat(expected)
        .hasMessageThat()
        .contains("Failed to get client TLS configuration from S2A.");
  }

  @Test
  public void createForClient_getsBadTlsVersionsFromServer_throwsError() throws Exception {
    writer.setBehavior(FakeWriter.Behavior.BAD_TLS_VERSION_RESPONSE);

    S2AConnectionException expected =
        assertThrows(
            S2AConnectionException.class,
            () ->
                SslContextFactory.createForClient(
                    stub, FAKE_TARGET_NAME, /* localIdentity= */ Optional.empty()));

    assertThat(expected)
        .hasMessageThat()
        .contains("Set of TLS versions received from S2A server is empty or not supported.");
  }

  @Test
  public void createForClient_nullStub_throwsError() throws Exception {
    writer.sendUnexpectedResponse();

    NullPointerException expected =
        assertThrows(
            NullPointerException.class,
            () ->
                SslContextFactory.createForClient(
                    /* stub= */ null, FAKE_TARGET_NAME, /* localIdentity= */ Optional.empty()));

    assertThat(expected).hasMessageThat().isEqualTo("stub should not be null.");
  }

  @Test
  public void createForClient_nullTargetName_throwsError() throws Exception {
    writer.sendUnexpectedResponse();

    NullPointerException expected =
        assertThrows(
            NullPointerException.class,
            () ->
                SslContextFactory.createForClient(
                    stub, /* targetName= */ null, /* localIdentity= */ Optional.empty()));

    assertThat(expected)
        .hasMessageThat()
        .isEqualTo("targetName should not be null on client side.");
  }
}