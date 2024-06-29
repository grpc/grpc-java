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
import io.grpc.TlsChannelCredentials;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@code S2AChannelCredentials}. */
@RunWith(JUnit4.class)
public final class S2AChannelCredentialsTest {
  @Test
  public void createBuilder_nullArgument_throwsException() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> S2AChannelCredentials.createBuilder(null));
  }

  @Test
  public void createBuilder_emptyAddress_throwsException() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> S2AChannelCredentials.createBuilder(""));
  }

  @Test
  public void setLocalSpiffeId_nullArgument_throwsException() throws Exception {
    assertThrows(
        NullPointerException.class,
        () -> S2AChannelCredentials.createBuilder("s2a_address").setLocalSpiffeId(null));
  }

  @Test
  public void setLocalHostname_nullArgument_throwsException() throws Exception {
    assertThrows(
        NullPointerException.class,
        () -> S2AChannelCredentials.createBuilder("s2a_address").setLocalHostname(null));
  }

  @Test
  public void setLocalUid_nullArgument_throwsException() throws Exception {
    assertThrows(
        NullPointerException.class,
        () -> S2AChannelCredentials.createBuilder("s2a_address").setLocalUid(null));
  }

  @Test
  public void build_withLocalSpiffeId_succeeds() throws Exception {
    assertThat(
            S2AChannelCredentials.createBuilder("s2a_address")
                .setLocalSpiffeId("spiffe://test")
                .build())
        .isNotNull();
  }

  @Test
  public void build_withLocalHostname_succeeds() throws Exception {
    assertThat(
            S2AChannelCredentials.createBuilder("s2a_address")
                .setLocalHostname("local_hostname")
                .build())
        .isNotNull();
  }

  @Test
  public void build_withLocalUid_succeeds() throws Exception {
    assertThat(S2AChannelCredentials.createBuilder("s2a_address").setLocalUid("local_uid").build())
        .isNotNull();
  }

  @Test
  public void build_withNoLocalIdentity_succeeds() throws Exception {
    assertThat(S2AChannelCredentials.createBuilder("s2a_address").build())
        .isNotNull();
  }
  
  @Test
  public void build_withTlsChannelCredentials_succeeds() throws Exception {
    assertThat(
            S2AChannelCredentials.createBuilder("s2a_address")
                .setLocalSpiffeId("spiffe://test")
                .setS2AChannelCredentials(getTlsChannelCredentials())
                .build())
        .isNotNull();
  }

  private static ChannelCredentials getTlsChannelCredentials() throws Exception {
    File clientCert = new File("src/test/resources/client_cert.pem");
    File clientKey = new File("src/test/resources/client_key.pem");
    File rootCert = new File("src/test/resources/root_cert.pem");
    return TlsChannelCredentials.newBuilder()
        .keyManager(clientCert, clientKey)
        .trustManager(rootCert)
        .build();
  }
}