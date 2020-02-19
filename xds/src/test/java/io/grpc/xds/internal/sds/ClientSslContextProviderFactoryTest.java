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

package io.grpc.xds.internal.sds;

import static com.google.common.truth.Truth.assertThat;

import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ClientSslContextProviderFactory}. */
@RunWith(JUnit4.class)
public class ClientSslContextProviderFactoryTest {

  private static final String CLIENT_PEM_FILE = "client.pem";
  private static final String CLIENT_KEY_FILE = "client.key";
  private static final String CA_PEM_FILE = "ca.pem";

  ClientSslContextProviderFactory clientSslContextProviderFactory =
      new ClientSslContextProviderFactory();

  @Test
  public void createSslContextProvider_allFilenames() {
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContextFromFilenames(
            CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);

    SslContextProvider<UpstreamTlsContext> sslContextProvider =
        clientSslContextProviderFactory.createSslContextProvider(upstreamTlsContext);
    assertThat(sslContextProvider).isNotNull();
  }

  @Test
  public void createSslContextProvider_sdsConfigForTlsCert_expectException() {
    CommonTlsContext commonTlsContext =
        CommonTlsContextTestsUtil.buildCommonTlsContextFromSdsConfigForTlsCertificate(
            /* name= */ "name", /* targetUri= */ "unix:/tmp/sds/path", CA_PEM_FILE);
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContext(commonTlsContext);

    try {
      SslContextProvider<UpstreamTlsContext> unused =
          clientSslContextProviderFactory.createSslContextProvider(upstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (UnsupportedOperationException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("UpstreamTlsContext to have all filenames or all SdsConfig");
    }
  }

  @Test
  public void createSslContextProvider_sdsConfigForCertValidationContext_expectException() {
    CommonTlsContext commonTlsContext =
        CommonTlsContextTestsUtil.buildCommonTlsContextFromSdsConfigForValidationContext(
            /* name= */ "name",
            /* targetUri= */ "unix:/tmp/sds/path",
            CLIENT_KEY_FILE,
            CLIENT_PEM_FILE);
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContext(commonTlsContext);

    try {
      SslContextProvider<UpstreamTlsContext> unused =
          clientSslContextProviderFactory.createSslContextProvider(upstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (UnsupportedOperationException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("UpstreamTlsContext to have all filenames or all SdsConfig");
    }
  }
}
