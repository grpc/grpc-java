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

import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link SecretManager}.
 */
@RunWith(JUnit4.class)
public class SecretManagerTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void createTest() throws IOException, ExecutionException, InterruptedException {
    ConfigSource configSource = TlsCertificateSecretProviderMapTest
        .createFileAndConfigSource(temporaryFolder);

    SecretManager secretManager = new SecretManager();
    SecretProvider<TlsCertificateStore> provider = secretManager
        .findOrCreateTlsCertificateProvider(configSource, "test");
    assertThat(provider).isNotNull();
    TlsCertificateStore tlsCertificateStore =
        TlsCertificateSecretProviderMapTest.getValueThruCallback(provider);
    assertThat(tlsCertificateStore).isNotNull();
    TlsCertificateStoreTest
        .verifyKeyAndCertsWithStrings(tlsCertificateStore, "pemContents", "crtContents");
  }

  @Test
  @SuppressWarnings("unused")
  public void nonFilePathConfigSource() {
    ConfigSource configSource =
        ConfigSource.newBuilder()
            .setApiConfigSource(ApiConfigSource.newBuilder().build())
            .build();

    //thrown.expect(UnsupportedOperationException.class);
    SecretManager secretManager = new SecretManager();
    try {
      SecretProvider<TlsCertificateStore> provider = secretManager
          .findOrCreateTlsCertificateProvider(configSource, "test");
      Assert.fail("no exception thrown");
    } catch (UnsupportedOperationException expected) {
      assertThat(expected).hasMessageThat()
          .contains("Only file based secret supported");
    }
  }

}
