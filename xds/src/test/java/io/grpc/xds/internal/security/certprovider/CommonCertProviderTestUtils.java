/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds.internal.security.certprovider;

import io.grpc.internal.FakeClock;
import io.grpc.internal.TimeProvider;
import io.grpc.internal.testing.TestUtils;
import io.grpc.util.CertificateUtils;
import io.grpc.xds.internal.security.certprovider.FileWatcherCertificateProviderProvider.ScheduledExecutorServiceFactory;
import java.io.IOException;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.ScheduledExecutorService;

public class CommonCertProviderTestUtils {

  static PrivateKey getPrivateKey(String resourceName)
          throws Exception {
    return CertificateUtils.getPrivateKey(
        TestUtils.class.getResourceAsStream("/certs/" + resourceName));
  }

  static X509Certificate getCertFromResourceName(String resourceName)
          throws IOException, CertificateException {
    try (InputStream cert = TestUtils.class.getResourceAsStream("/certs/" + resourceName)) {
      return CertificateUtils.getX509Certificates(cert)[0];
    }
  }

  /** Allow tests to register a provider using test clock.
  */
  public static void register(final FakeClock fakeClock) {
    FileWatcherCertificateProviderProvider tmp = new FileWatcherCertificateProviderProvider(
        FileWatcherCertificateProvider.Factory.getInstance(),
        new ScheduledExecutorServiceFactory() {

            @Override
            ScheduledExecutorService create() {
              return fakeClock.getScheduledExecutorService();
            }
          },
          TimeProvider.SYSTEM_TIME_PROVIDER);
    CertificateProviderRegistry.getInstance().register(tmp);
  }

  public static void register0() {
    CertificateProviderRegistry.getInstance().register(
            new FileWatcherCertificateProviderProvider());
  }
}
