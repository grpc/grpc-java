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

package io.grpc.s2a;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.TlsChannelCredentials;
import java.io.InputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@code S2AChannelCredentials}. */
@RunWith(JUnit4.class)
public final class S2AChannelCredentialsTest {
  @Test
  public void newBuilder_nullAddress_throwsException() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> S2AChannelCredentials.newBuilder(null,
        InsecureChannelCredentials.create()));
  }

  @Test
  public void newBuilder_emptyAddress_throwsException() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> S2AChannelCredentials.newBuilder("",
        InsecureChannelCredentials.create()));
  }

  @Test
  public void newBuilder_nullChannelCreds_throwsException() throws Exception {
    assertThrows(NullPointerException.class, () -> S2AChannelCredentials
        .newBuilder("s2a_address", null));
  }

  @Test
  public void setLocalSpiffeId_nullArgument_throwsException() throws Exception {
    assertThrows(
        NullPointerException.class,
        () -> S2AChannelCredentials.newBuilder("s2a_address",
            InsecureChannelCredentials.create()).setLocalSpiffeId(null));
  }

  @Test
  public void setLocalHostname_nullArgument_throwsException() throws Exception {
    assertThrows(
        NullPointerException.class,
        () -> S2AChannelCredentials.newBuilder("s2a_address",
            InsecureChannelCredentials.create()).setLocalHostname(null));
  }

  @Test
  public void setLocalUid_nullArgument_throwsException() throws Exception {
    assertThrows(
        NullPointerException.class,
        () -> S2AChannelCredentials.newBuilder("s2a_address",
            InsecureChannelCredentials.create()).setLocalUid(null));
  }

  @Test
  public void build_withLocalSpiffeId_succeeds() throws Exception {
    assertThat(
            S2AChannelCredentials.newBuilder("s2a_address", InsecureChannelCredentials.create())
                .setLocalSpiffeId("spiffe://test")
                .build())
        .isNotNull();
  }

  @Test
  public void build_withLocalHostname_succeeds() throws Exception {
    assertThat(
            S2AChannelCredentials.newBuilder("s2a_address", InsecureChannelCredentials.create())
                .setLocalHostname("local_hostname")
                .build())
        .isNotNull();
  }

  @Test
  public void build_withLocalUid_succeeds() throws Exception {
    assertThat(S2AChannelCredentials.newBuilder("s2a_address",
        InsecureChannelCredentials.create()).setLocalUid("local_uid").build())
        .isNotNull();
  }

  @Test
  public void build_withNoLocalIdentity_succeeds() throws Exception {
    assertThat(S2AChannelCredentials.newBuilder("s2a_address",
        InsecureChannelCredentials.create()).build())
        .isNotNull();
  }

  @Test
  public void build_withUseMtlsToS2ANoLocalIdentity_success() throws Exception {
    ChannelCredentials s2aChannelCredentials = getTlsChannelCredentials();
    assertThat(
            S2AChannelCredentials.newBuilder("s2a_address", s2aChannelCredentials)
                .build())
        .isNotNull();
  }

  @Test
  public void build_withUseMtlsToS2AWithLocalUid_success() throws Exception {
    ChannelCredentials s2aChannelCredentials = getTlsChannelCredentials();
    assertThat(
            S2AChannelCredentials.newBuilder("s2a_address", s2aChannelCredentials)
                .setLocalUid("local_uid")
                .build())
        .isNotNull();
  }

  private static ChannelCredentials getTlsChannelCredentials() throws Exception {
    ClassLoader classLoader = S2AChannelCredentialsTest.class.getClassLoader();
    InputStream privateKey = classLoader.getResourceAsStream("client_key.pem");
    InputStream certChain = classLoader.getResourceAsStream("client_cert.pem");
    InputStream trustBundle = classLoader.getResourceAsStream("root_cert.pem");
    return TlsChannelCredentials.newBuilder()
      .keyManager(certChain, privateKey)
      .trustManager(trustBundle)
      .build();
  }
}