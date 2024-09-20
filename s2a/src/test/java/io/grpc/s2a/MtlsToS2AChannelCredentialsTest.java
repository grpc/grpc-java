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

@RunWith(JUnit4.class)
public final class MtlsToS2AChannelCredentialsTest {
  @Test
  public void newBuilder_nullAddress_throwsException() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MtlsToS2AChannelCredentials.newBuilder(
                /* s2aAddress= */ null,
                /* privateKeyPath= */ "src/test/resources/client_key.pem",
                /* certChainPath= */ "src/test/resources/client_cert.pem",
                /* trustBundlePath= */ "src/test/resources/root_cert.pem"));
  }

  @Test
  public void newBuilder_nullPrivateKeyPath_throwsException() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MtlsToS2AChannelCredentials.newBuilder(
                /* s2aAddress= */ "s2a_address",
                /* privateKeyPath= */ null,
                /* certChainPath= */ "src/test/resources/client_cert.pem",
                /* trustBundlePath= */ "src/test/resources/root_cert.pem"));
  }

  @Test
  public void newBuilder_nullCertChainPath_throwsException() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MtlsToS2AChannelCredentials.newBuilder(
                /* s2aAddress= */ "s2a_address",
                /* privateKeyPath= */ "src/test/resources/client_key.pem",
                /* certChainPath= */ null,
                /* trustBundlePath= */ "src/test/resources/root_cert.pem"));
  }

  @Test
  public void newBuilder_nullTrustBundlePath_throwsException() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MtlsToS2AChannelCredentials.newBuilder(
                /* s2aAddress= */ "s2a_address",
                /* privateKeyPath= */ "src/test/resources/client_key.pem",
                /* certChainPath= */ "src/test/resources/client_cert.pem",
                /* trustBundlePath= */ null));
  }

  @Test
  public void newBuilder_emptyAddress_throwsException() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MtlsToS2AChannelCredentials.newBuilder(
                /* s2aAddress= */ "",
                /* privateKeyPath= */ "src/test/resources/client_key.pem",
                /* certChainPath= */ "src/test/resources/client_cert.pem",
                /* trustBundlePath= */ "src/test/resources/root_cert.pem"));
  }

  @Test
  public void newBuilder_emptyPrivateKeyPath_throwsException() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MtlsToS2AChannelCredentials.newBuilder(
                /* s2aAddress= */ "s2a_address",
                /* privateKeyPath= */ "",
                /* certChainPath= */ "src/test/resources/client_cert.pem",
                /* trustBundlePath= */ "src/test/resources/root_cert.pem"));
  }

  @Test
  public void newBuilder_emptyCertChainPath_throwsException() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MtlsToS2AChannelCredentials.newBuilder(
                /* s2aAddress= */ "s2a_address",
                /* privateKeyPath= */ "src/test/resources/client_key.pem",
                /* certChainPath= */ "",
                /* trustBundlePath= */ "src/test/resources/root_cert.pem"));
  }

  @Test
  public void newBuilder_emptyTrustBundlePath_throwsException() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MtlsToS2AChannelCredentials.newBuilder(
                /* s2aAddress= */ "s2a_address",
                /* privateKeyPath= */ "src/test/resources/client_key.pem",
                /* certChainPath= */ "src/test/resources/client_cert.pem",
                /* trustBundlePath= */ ""));
  }

  @Test
  public void build_s2AChannelCredentials_success() throws Exception {
    assertThat(
            MtlsToS2AChannelCredentials.newBuilder(
                /* s2aAddress= */ "s2a_address",
                /* privateKeyPath= */ "src/test/resources/client_key.pem",
                /* certChainPath= */ "src/test/resources/client_cert.pem",
                /* trustBundlePath= */ "src/test/resources/root_cert.pem")
                .build())
        .isInstanceOf(S2AChannelCredentials.Builder.class);
  }
}