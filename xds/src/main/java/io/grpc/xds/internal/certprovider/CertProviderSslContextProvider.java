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

import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CertificateProviderInstance;
import io.grpc.xds.Bootstrapper.CertificateProviderInfo;
import io.grpc.xds.EnvoyServerProtoData.BaseTlsContext;
import io.grpc.xds.internal.sds.DynamicSslContextProvider;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Base class for {@link CertProviderClientSslContextProvider}. */
abstract class CertProviderSslContextProvider extends DynamicSslContextProvider implements
    CertificateProvider.Watcher {

  @Nullable private final CertificateProviderStore.Handle certHandle;
  @Nullable private final CertificateProviderStore.Handle rootCertHandle;
  @Nullable private final CertificateProviderInstance certInstance;
  @Nullable private final CertificateProviderInstance rootCertInstance;
  @Nullable protected PrivateKey savedKey;
  @Nullable protected List<X509Certificate> savedCertChain;
  @Nullable protected List<X509Certificate> savedTrustedRoots;

  protected CertProviderSslContextProvider(
      Node node,
      Map<String, CertificateProviderInfo> certProviders,
      CertificateProviderInstance certInstance,
      CertificateProviderInstance rootCertInstance,
      CertificateValidationContext staticCertValidationContext,
      BaseTlsContext tlsContext,
      CertificateProviderStore certificateProviderStore) {
    super(tlsContext, staticCertValidationContext);
    this.certInstance = certInstance;
    this.rootCertInstance = rootCertInstance;
    String certInstanceName = null;
    if (certInstance != null && certInstance.isInitialized()) {
      certInstanceName = certInstance.getInstanceName();
      CertificateProviderInfo certProviderInstanceConfig =
          getCertProviderConfig(certProviders, certInstanceName);
      certHandle =
          certificateProviderStore.createOrGetProvider(
              certInstance.getCertificateName(),
              certProviderInstanceConfig.getPluginName(),
              certProviderInstanceConfig.getConfig(),
              this,
              true);
    } else {
      certHandle = null;
    }
    if (rootCertInstance != null
        && rootCertInstance.isInitialized()
        && !rootCertInstance.getInstanceName().equals(certInstanceName)) {
      CertificateProviderInfo certProviderInstanceConfig =
          getCertProviderConfig(certProviders, rootCertInstance.getInstanceName());
      rootCertHandle =
          certificateProviderStore.createOrGetProvider(
              rootCertInstance.getCertificateName(),
              certProviderInstanceConfig.getPluginName(),
              certProviderInstanceConfig.getConfig(),
              this,
              true);
    } else {
      rootCertHandle = null;
    }
  }

  private static CertificateProviderInfo getCertProviderConfig(
      Map<String, CertificateProviderInfo> certProviders, String pluginInstanceName) {
    return certProviders.get(pluginInstanceName);
  }

  @Override
  public final void updateCertificate(PrivateKey key, List<X509Certificate> certChain) {
    savedKey = key;
    savedCertChain = certChain;
    updateSslContextWhenReady();
  }

  @Override
  public final void updateTrustedRoots(List<X509Certificate> trustedRoots) {
    savedTrustedRoots = trustedRoots;
    updateSslContextWhenReady();
  }

  private void updateSslContextWhenReady() {
    if (isMtls()) {
      if (savedKey != null && savedTrustedRoots != null) {
        updateSslContext();
        clearKeysAndCerts();
      }
    } else if (isClientSideTls()) {
      if (savedTrustedRoots != null) {
        updateSslContext();
        clearKeysAndCerts();
      }
    } else if (isServerSideTls()) {
      if (savedKey != null) {
        updateSslContext();
        clearKeysAndCerts();
      }
    }
  }

  private void clearKeysAndCerts() {
    savedKey = null;
    savedTrustedRoots = null;
    savedCertChain = null;
  }

  protected final boolean isMtls() {
    return certInstance != null && rootCertInstance != null;
  }

  protected final boolean isClientSideTls() {
    return rootCertInstance != null && certInstance == null;
  }

  protected final boolean isServerSideTls() {
    return certInstance != null && rootCertInstance == null;
  }

  @Override
  protected final CertificateValidationContext generateCertificateValidationContext() {
    return staticCertificateValidationContext;
  }

  @Override
  public final void close() {
    if (certHandle != null) {
      certHandle.close();
    }
    if (rootCertHandle != null) {
      rootCertHandle.close();
    }
  }
}
