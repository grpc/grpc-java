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

import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link TlsCertificateStore}.
 */
@RunWith(JUnit4.class)
public class TlsCertificateStoreTest {

  static void verifyKeyAndCertsWithStrings(TlsCertificateStore tlsCertificateStore, String s1,
      String s2) throws IOException {
    verifyKeyAndCertsWithStrings(tlsCertificateStore.getPrivateKey(), s1);
    verifyKeyAndCertsWithStrings(tlsCertificateStore.getCertChain(), s2);
  }

  private static void verifyKeyAndCertsWithStrings(ByteString byteString, String s)
      throws IOException {
    assertThat(byteString).isNotNull();
    assertThat(byteString.toStringUtf8()).isEqualTo(s);
  }

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

    verifyKeyAndCertsWithStrings(tlsCertificateStore, "test-privateKey", "test-certChain");
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

    ByteString privateKeyByteString = tlsCertificateStore.getPrivateKey();
    assertThat(privateKeyByteString).isNotNull();
    assertThat(privateKeyByteString.toByteArray()).isEqualTo(privateKeyBytes);

    ByteString certChainByteString = tlsCertificateStore.getCertChain();
    assertThat(certChainByteString).isNotNull();
    assertThat(certChainByteString.toByteArray()).isEqualTo(certChainBytes);
  }

  @Test
  public void privateKeyNotSet() {
    DataSource certChain =
        DataSource.newBuilder()
            .setInlineString("test-certChain")
            .build();
    TlsCertificate tlsCertificate =
        TlsCertificate.newBuilder()
            .setCertificateChain(certChain)
            .build();

    try {
      new TlsCertificateStore(tlsCertificate);
      Assert.fail("no exception thrown");
    } catch (UnsupportedOperationException expected) {
      assertThat(expected).hasMessageThat()
          .contains("dataSource of type SPECIFIER_NOT_SET not supported");
    }
  }

  @Test
  public void certChainNotSet() {
    DataSource privateKey =
        DataSource.newBuilder()
            .setInlineString("test-privateKey")
            .build();
    TlsCertificate tlsCertificate =
        TlsCertificate.newBuilder()
            .setPrivateKey(privateKey)
            .build();
    try {
      new TlsCertificateStore(tlsCertificate);
      Assert.fail("no exception thrown");
    } catch (UnsupportedOperationException expected) {
      assertThat(expected).hasMessageThat()
          .contains("dataSource of type SPECIFIER_NOT_SET not supported");
    }
  }

}
