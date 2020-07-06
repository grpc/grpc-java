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
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_PEM_FILE;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ServerSslContextProviderFactory}. */
@RunWith(JUnit4.class)
public class ServerSslContextProviderFactoryTest {

  ServerSslContextProviderFactory serverSslContextProviderFactory =
      new ServerSslContextProviderFactory();

  @Test
  public void createSslContextProvider_allFilenames() {
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextFromFilenames(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, CA_PEM_FILE);

    SslContextProvider sslContextProvider =
        serverSslContextProviderFactory.createSslContextProvider(downstreamTlsContext);
    assertThat(sslContextProvider).isNotNull();
  }

  @Test
  public void createSslContextProvider_sdsConfigForTlsCert_expectException() {
    CommonTlsContext commonTlsContext =
        CommonTlsContextTestsUtil.buildCommonTlsContextFromSdsConfigForTlsCertificate(
            "name", "unix:/tmp/sds/path", CA_PEM_FILE);
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildInternalDownstreamTlsContext(
            commonTlsContext, /* requireClientCert= */ false);

    try {
      SslContextProvider unused =
          serverSslContextProviderFactory.createSslContextProvider(downstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (UnsupportedOperationException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("DownstreamTlsContext to have all filenames or all SdsConfig");
    }
  }

  @Test
  public void createSslContextProvider_sdsConfigForCertValidationContext_expectException() {
    CommonTlsContext commonTlsContext =
        CommonTlsContextTestsUtil.buildCommonTlsContextFromSdsConfigForValidationContext(
            "name", "unix:/tmp/sds/path", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE);
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildInternalDownstreamTlsContext(
            commonTlsContext, /* requireClientCert= */ false);

    try {
      SslContextProvider unused =
          serverSslContextProviderFactory.createSslContextProvider(downstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (UnsupportedOperationException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("DownstreamTlsContext to have all filenames or all SdsConfig");
    }
  }
}
