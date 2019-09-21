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

package io.grpc.xds.sds;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.ByteStreams;
import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link TlsCertificateStore}.
 */
@RunWith(JUnit4.class)
public class TlsCertificateStoreTest {

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  @Test
  public void convertFromTlsCertificateUsingString() throws IOException {
    DataSource privateKey =
        DataSource.newBuilder()
            .setInlineString("test-privateKey")
            .build();
    DataSource certChain =
        DataSource.newBuilder()
            .setInlineString("test-certChain")
            .build();

    TlsCertificate tlsCertificate =
        TlsCertificate.newBuilder()
            .setPrivateKey(privateKey)
            .setCertificateChain(certChain)
            .build();

    TlsCertificateStore tlsCertificateStore = new TlsCertificateStore(tlsCertificate);

    verifyInputStreamAndString(tlsCertificateStore, "test-privateKey", "test-certChain");
  }

  public static void verifyInputStreamAndString(TlsCertificateStore tlsCertificateStore, String s1,
      String s2) throws IOException {
    verifyInputStreamAndString(tlsCertificateStore.getPrivateKeyStream(), s1);
    verifyInputStreamAndString(tlsCertificateStore.getCertChainStream(), s2);
  }

  private static void verifyInputStreamAndString(InputStream privateKeyStream, String s)
      throws IOException {
    InputStream privateKeyInputStream = privateKeyStream;
    assertThat(privateKeyInputStream).isNotNull();
    assertThat(new String(ByteStreams.toByteArray(privateKeyInputStream), UTF_8)).isEqualTo(s);
  }

  @Test
  public void convertFromTlsCertificateUsingBytes() throws IOException {
    byte[] privateKeyBytes = {1, 2, 3, 4};
    byte[] certChainBytes = {4, 3, 2, 1};

    DataSource privateKey =
        DataSource.newBuilder()
            .setInlineBytes(ByteString.copyFrom(privateKeyBytes))
            .build();
    DataSource certChain =
        DataSource.newBuilder()
            .setInlineBytes(ByteString.copyFrom(certChainBytes))
            .build();

    TlsCertificate tlsCertificate =
        TlsCertificate.newBuilder()
            .setPrivateKey(privateKey)
            .setCertificateChain(certChain)
            .build();

    TlsCertificateStore tlsCertificateStore = new TlsCertificateStore(tlsCertificate);

    InputStream privateKeyInputStream = tlsCertificateStore.getPrivateKeyStream();
    assertThat(privateKeyInputStream).isNotNull();
    assertThat(ByteStreams.toByteArray(privateKeyInputStream)).isEqualTo(privateKeyBytes);

    InputStream certChainInputStream = tlsCertificateStore.getCertChainStream();
    assertThat(certChainInputStream).isNotNull();
    assertThat(ByteStreams.toByteArray(certChainInputStream)).isEqualTo(certChainBytes);
  }

  @Test
  public void privateKeyNotSet() {
    thrown.expect(IllegalArgumentException.class);
    DataSource certChain =
        DataSource.newBuilder()
            .setInlineString("test-certChain")
            .build();
    TlsCertificate tlsCertificate =
        TlsCertificate.newBuilder()
            .setCertificateChain(certChain)
            .build();

    new TlsCertificateStore(tlsCertificate);
  }

  @Test
  public void certChainNotSet() {
    thrown.expect(IllegalArgumentException.class);
    DataSource privateKey =
        DataSource.newBuilder()
            .setInlineString("test-privateKey")
            .build();
    TlsCertificate tlsCertificate =
        TlsCertificate.newBuilder()
            .setPrivateKey(privateKey)
            .build();

    new TlsCertificateStore(tlsCertificate);
  }

}
