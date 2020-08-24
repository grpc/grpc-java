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

package io.grpc.xds.internal.certprovider;

public class TestCertificateProvider extends CertificateProvider {
  Object config;
  CertificateProviderProvider certProviderProvider;
  int closeCalled = 0;
  int startCalled = 0;

  /** Creates a TestCertificateProvider instance. */
  public TestCertificateProvider(
      DistributorWatcher watcher,
      boolean notifyCertUpdates,
      Object config,
      CertificateProviderProvider certificateProviderProvider,
      boolean throwExceptionForCertUpdates) {
    super(watcher, notifyCertUpdates);
    if (throwExceptionForCertUpdates && notifyCertUpdates) {
      throw new UnsupportedOperationException("Provider does not support Certificate Updates.");
    }
    this.config = config;
    this.certProviderProvider = certificateProviderProvider;
  }

  @Override
  public void close() {
    closeCalled++;
  }

  @Override
  public void start() {
    startCalled++;
  }

  static void createAndRegisterProviderProvider(
      CertificateProviderRegistry certificateProviderRegistry,
      final CertificateProvider.DistributorWatcher[] watcherCaptor,
      String testca,
      final int index) {
    final CertificateProviderProvider mockProviderProviderTestCa =
        new TestCertificateProviderProvider(testca, watcherCaptor, index);
    certificateProviderRegistry.register(mockProviderProviderTestCa);
  }

  private static class TestCertificateProviderProvider implements CertificateProviderProvider {

    private final String testCa;
    private final CertificateProvider.DistributorWatcher[] watcherCaptor;
    private final int index;

    TestCertificateProviderProvider(
        String testCa, CertificateProvider.DistributorWatcher[] watcherCaptor, int index) {
      this.testCa = testCa;
      this.watcherCaptor = watcherCaptor;
      this.index = index;
    }

    @Override
    public String getName() {
      return testCa;
    }

    @Override
    public CertificateProvider createCertificateProvider(
        Object config, DistributorWatcher watcher, boolean notifyCertUpdates) {
      watcherCaptor[index] = watcher;
      return new TestCertificateProvider(watcher, true, config, this, false);
    }
  }
}
