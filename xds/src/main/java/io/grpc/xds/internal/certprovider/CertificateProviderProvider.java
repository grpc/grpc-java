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

import io.grpc.Internal;
import io.grpc.xds.internal.certprovider.CertificateProvider.Watcher;

/**
 * Provider of {@link CertificateProvider}s. Implemented by the implementer of the plugin. We may
 * move this out of the internal package and make this an official API in the future.
 */
@Internal
public interface CertificateProviderProvider {
  /** Returns the unique name of the {@link CertificateProvider} plugin. */
  String getName();

  /**
   * Creates a {@link CertificateProvider} plugin.
   *
   * @param config configuration needed by the Provider to create the CertificateProvider. A form of
   *     JSON that the Provider understands e.g. a string or a key-value Map.
   * @param watcher A {@link Watcher} to receive updates from the CertificateProvider
   * @param notifyCertUpdates if true, the provider is required to call the watcher’s
   *     updateCertificate method. Implies the Provider is capable of minting certificates. Used
   *     by server-side and mTLS client-side. Note the Provider is always required  to call
   *     updateTrustedRoots to provide trusted-root updates.
   * @throws IllegalArgumentException in case of errors in processing config.
   * @throws UnsupportedOperationException if the plugin is incapable of sending cert updates when
   *     notifyCertUpdates is true.
   */
  CertificateProvider createCertificateProvider(
      Object config, CertificateProvider.DistributorWatcher watcher, boolean notifyCertUpdates);
}
