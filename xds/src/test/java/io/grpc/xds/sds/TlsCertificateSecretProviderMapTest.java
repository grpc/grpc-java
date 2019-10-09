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

import com.google.common.util.concurrent.MoreExecutors;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link TlsCertificateSecretProviderMap}.
 */
@RunWith(JUnit4.class)
public class TlsCertificateSecretProviderMapTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  /**
   * Utility function for creation of test files in a temp folder.
   * Used in other classes
   *
   * @param temporaryFolder   temporary folder to use
   * @return a config source representing the file based secret
   * @throws IOException represents IO exception
   */
  static ConfigSource createFileAndConfigSource(TemporaryFolder temporaryFolder)
      throws IOException {
    File filePath = TlsCertificateSecretVolumeSecretProviderTest
        .createTestCertFiles(temporaryFolder);

    return ConfigSource.newBuilder()
        .setPath(filePath.getPath())
        .build();
  }

  /**
   * Helper method to get the value thru directExecutore callback. Used by other classes.
   */
  static TlsCertificateStore getValueThruCallback(SecretProvider<TlsCertificateStore> provider) {
    SslContextSecretVolumeSecretProviderTest.TestCallback<TlsCertificateStore> testCallback
            = new SslContextSecretVolumeSecretProviderTest.TestCallback<>();
    provider.addCallback(testCallback, MoreExecutors.directExecutor());
    return testCallback.updatedSecret;
  }

  @Test
  public void createTest() throws IOException, ExecutionException, InterruptedException {
    ConfigSource configSource = createFileAndConfigSource(temporaryFolder);
    TlsCertificateSecretProviderMap map = new TlsCertificateSecretProviderMap();
    SecretProvider<TlsCertificateStore> provider = map.findOrCreate(configSource, "test");
    assertThat(provider).isNotNull();
    TlsCertificateStore tlsCertificateStore = getValueThruCallback(provider);
    assertThat(tlsCertificateStore).isNotNull();
    TlsCertificateStoreTest
        .verifyKeyAndCertsWithStrings(tlsCertificateStore, "pemContents", "crtContents");
  }

  @Test
  public void nonFilePathConfigSource() {
    ConfigSource configSource =
        ConfigSource.newBuilder()
            .setApiConfigSource(ApiConfigSource.newBuilder().build())
            .build();

    TlsCertificateSecretProviderMap map = new TlsCertificateSecretProviderMap();
    try {
      SecretProvider<TlsCertificateStore> unused = map.findOrCreate(configSource, "test");
      Assert.fail("no exception thrown");
    } catch (UnsupportedOperationException expected) {
      assertThat(expected).hasMessageThat()
          .contains("Only file based secret supported");
    }
  }

}
