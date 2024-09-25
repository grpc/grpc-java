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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@code S2AChannelCredentials}. */
@RunWith(JUnit4.class)
public final class S2AChannelCredentialsTest {
  @Test
  public void newBuilder_nullArgument_throwsException() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> S2AChannelCredentials.newBuilder(null));
  }

  @Test
  public void newBuilder_emptyAddress_throwsException() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> S2AChannelCredentials.newBuilder(""));
  }

  @Test
  public void setLocalSpiffeId_nullArgument_throwsException() throws Exception {
    assertThrows(
        NullPointerException.class,
        () -> S2AChannelCredentials.newBuilder("s2a_address").setLocalSpiffeId(null));
  }

  @Test
  public void setLocalHostname_nullArgument_throwsException() throws Exception {
    assertThrows(
        NullPointerException.class,
        () -> S2AChannelCredentials.newBuilder("s2a_address").setLocalHostname(null));
  }

  @Test
  public void setLocalUid_nullArgument_throwsException() throws Exception {
    assertThrows(
        NullPointerException.class,
        () -> S2AChannelCredentials.newBuilder("s2a_address").setLocalUid(null));
  }

  @Test
  public void build_withLocalSpiffeId_succeeds() throws Exception {
    assertThat(
            S2AChannelCredentials.newBuilder("s2a_address")
                .setLocalSpiffeId("spiffe://test")
                .build())
        .isNotNull();
  }

  @Test
  public void build_withLocalHostname_succeeds() throws Exception {
    assertThat(
            S2AChannelCredentials.newBuilder("s2a_address")
                .setLocalHostname("local_hostname")
                .build())
        .isNotNull();
  }

  @Test
  public void build_withLocalUid_succeeds() throws Exception {
    assertThat(S2AChannelCredentials.newBuilder("s2a_address").setLocalUid("local_uid").build())
        .isNotNull();
  }

  @Test
  public void build_withNoLocalIdentity_succeeds() throws Exception {
    assertThat(S2AChannelCredentials.newBuilder("s2a_address").build())
        .isNotNull();
  }
  
  @Test
  public void build_withUseMtlsToS2ANoCredetials_throwsException() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () -> S2AChannelCredentials.newBuilder("s2a_address").setUseMtlsToS2A(true).build());
  }

  @Test
  public void build_withUseMtlsToS2ANullS2AAddress_throwsException() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            S2AChannelCredentials.newBuilder(null)
                .setUseMtlsToS2A(true)
                .setPrivateKeyPath("src/test/resources/client_key.pem")
                .setCertChainPath("src/test/resources/client_cert.pem")
                .setTrustBundlePath("src/test/resources/root_cert.pem")
                .build());
  }

  @Test
  public void build_withUseMtlsToS2AEmptyS2AAddress_throwsException() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            S2AChannelCredentials.newBuilder("")
                .setUseMtlsToS2A(true)
                .setPrivateKeyPath("src/test/resources/client_key.pem")
                .setCertChainPath("src/test/resources/client_cert.pem")
                .setTrustBundlePath("src/test/resources/root_cert.pem")
                .build());
  }

  @Test
  public void build_withUseMtlsToS2A_nullPrivateKeyPath_throwsException() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () ->
            S2AChannelCredentials.newBuilder("s2a_address")
                .setUseMtlsToS2A(true)
                .setPrivateKeyPath(null)
                .setCertChainPath("src/test/resources/client_cert.pem")
                .setTrustBundlePath("src/test/resources/root_cert.pem")
                .build());
  }

  @Test
  public void build_withUseMtlsToS2A_nullCertChainPath_throwsException() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () ->
            S2AChannelCredentials.newBuilder("s2a_address")
                .setUseMtlsToS2A(true)
                .setPrivateKeyPath("src/test/resources/client_key.pem")
                .setCertChainPath(null)
                .setTrustBundlePath("src/test/resources/root_cert.pem")
                .build());
  }

  @Test
  public void build_withUseMtlsToS2A_nullTrustBundlePath_throwsException() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () ->
            S2AChannelCredentials.newBuilder("s2a_address")
                .setUseMtlsToS2A(true)
                .setPrivateKeyPath("src/test/resources/client_key.pem")
                .setCertChainPath("src/test/resources/client_cert.pem")
                .setTrustBundlePath(null)
                .build());
  }

  @Test
  public void build_withUseMtlsToS2A_emptyPrivateKeyPath_throwsException() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () ->
            S2AChannelCredentials.newBuilder("s2a_address")
                .setUseMtlsToS2A(true)
                .setPrivateKeyPath("")
                .setCertChainPath("src/test/resources/client_cert.pem")
                .setTrustBundlePath("src/test/resources/root_cert.pem")
                .build());
  }

  @Test
  public void build_withUseMtlsToS2A_emptyCertChainPath_throwsException() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () ->
            S2AChannelCredentials.newBuilder("s2a_address")
                .setUseMtlsToS2A(true)
                .setPrivateKeyPath("src/test/resources/client_key.pem")
                .setCertChainPath("")
                .setTrustBundlePath("src/test/resources/root_cert.pem")
                .build());
  }

  @Test
  public void build_withUseMtlsToS2A_emptyTrustBundlePath_throwsException() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () ->
            S2AChannelCredentials.newBuilder("s2a_address")
                .setUseMtlsToS2A(true)
                .setPrivateKeyPath("src/test/resources/client_key.pem")
                .setCertChainPath("src/test/resources/client_cert.pem")
                .setTrustBundlePath("")
                .build());
  }

  @Test
  public void build_withUseMtlsToS2ANoLocalIdentity_success() throws Exception {
    assertThat(
            S2AChannelCredentials.newBuilder("s2a_address")
                .setUseMtlsToS2A(true)
                .setPrivateKeyPath("src/test/resources/client_key.pem")
                .setCertChainPath("src/test/resources/client_cert.pem")
                .setTrustBundlePath("src/test/resources/root_cert.pem")
                .build())
        .isNotNull();
  }

  @Test
  public void build_withUseMtlsToS2AWithLocalUid_success() throws Exception {
    assertThat(
            S2AChannelCredentials.newBuilder("s2a_address")
                .setUseMtlsToS2A(true)
                .setPrivateKeyPath("src/test/resources/client_key.pem")
                .setCertChainPath("src/test/resources/client_cert.pem")
                .setTrustBundlePath("src/test/resources/root_cert.pem")
                .setLocalUid("local_uid")
                .build())
        .isNotNull();
  }
}