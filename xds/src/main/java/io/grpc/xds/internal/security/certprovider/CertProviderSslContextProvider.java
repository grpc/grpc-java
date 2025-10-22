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

import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CertificateProviderInstance;
import io.grpc.xds.EnvoyServerProtoData.BaseTlsContext;
import io.grpc.xds.client.Bootstrapper.CertificateProviderInfo;
import io.grpc.xds.internal.security.CommonTlsContextUtil;
import io.grpc.xds.internal.security.DynamicSslContextProvider;
import java.io.Closeable;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Base class for {@link CertProviderClientSslContextProvider}. */
abstract class CertProviderSslContextProvider extends DynamicSslContextProvider implements
    CertificateProvider.Watcher {

  @Nullable private final NoExceptionCloseable certHandle;
  @Nullable private final NoExceptionCloseable rootCertHandle;
  @Nullable private final CertificateProviderInstance certInstance;
  @Nullable protected final CertificateProviderInstance rootCertInstance;
  @Nullable protected PrivateKey savedKey;
  @Nullable protected List<X509Certificate> savedCertChain;
  @Nullable protected List<X509Certificate> savedTrustedRoots;
  @Nullable protected Map<String, List<X509Certificate>> savedSpiffeTrustMap;
  private final boolean isUsingSystemRootCerts;

  protected CertProviderSslContextProvider(
      Node node,
      @Nullable Map<String, CertificateProviderInfo> certProviders,
      CertificateProviderInstance certInstance,
      CertificateProviderInstance rootCertInstance,
      CertificateValidationContext staticCertValidationContext,
      BaseTlsContext tlsContext,
      CertificateProviderStore certificateProviderStore) {
    super(tlsContext, staticCertValidationContext);
    this.certInstance = certInstance;
    this.rootCertInstance = rootCertInstance;
    this.isUsingSystemRootCerts = rootCertInstance == null
        && CommonTlsContextUtil.isUsingSystemRootCerts(tlsContext.getCommonTlsContext());
    boolean createCertInstance = certInstance != null && certInstance.isInitialized();
    boolean createRootCertInstance = rootCertInstance != null && rootCertInstance.isInitialized();
    boolean sharedCertInstance = createCertInstance && createRootCertInstance
        && rootCertInstance.getInstanceName().equals(certInstance.getInstanceName());
    if (createCertInstance) {
      CertificateProviderInfo certProviderInstanceConfig =
          getCertProviderConfig(certProviders, certInstance.getInstanceName());
      CertificateProvider.Watcher watcher = this;
      if (!sharedCertInstance && !isUsingSystemRootCerts) {
        watcher = new IgnoreUpdatesWatcher(watcher, /* ignoreRootCertUpdates= */ true);
      }
      // TODO: Previously we'd hang if certProviderInstanceConfig were null or
      // certInstance.isInitialized() == false. Now we'll proceed. Those should be errors, or are
      // they impossible and should be assertions?
      certHandle = certProviderInstanceConfig == null ? null
          : certificateProviderStore.createOrGetProvider(
              certInstance.getCertificateName(),
              certProviderInstanceConfig.pluginName(),
              certProviderInstanceConfig.config(),
              watcher,
              true)::close;
    } else {
      certHandle = null;
    }
    if (createRootCertInstance && !sharedCertInstance) {
      CertificateProviderInfo certProviderInstanceConfig =
          getCertProviderConfig(certProviders, rootCertInstance.getInstanceName());
      rootCertHandle = certProviderInstanceConfig == null ? null
          : certificateProviderStore.createOrGetProvider(
              rootCertInstance.getCertificateName(),
              certProviderInstanceConfig.pluginName(),
              certProviderInstanceConfig.config(),
              new IgnoreUpdatesWatcher(this, /* ignoreRootCertUpdates= */ false),
              false)::close;
    } else if (rootCertInstance == null
        && CommonTlsContextUtil.isUsingSystemRootCerts(tlsContext.getCommonTlsContext())) {
      SystemRootCertificateProvider systemRootProvider = new SystemRootCertificateProvider(this);
      systemRootProvider.start();
      rootCertHandle = systemRootProvider::close;
    } else {
      rootCertHandle = null;
    }
  }

  private static CertificateProviderInfo getCertProviderConfig(
      @Nullable Map<String, CertificateProviderInfo> certProviders, String pluginInstanceName) {
    return certProviders != null ? certProviders.get(pluginInstanceName) : null;
  }

  @Nullable
  protected static CertificateProviderInstance getCertProviderInstance(
      CommonTlsContext commonTlsContext) {
    if (commonTlsContext.hasTlsCertificateProviderInstance()) {
      return CommonTlsContextUtil.convert(commonTlsContext.getTlsCertificateProviderInstance());
    }
    // Fall back to deprecated field for backward compatibility with Istio
    @SuppressWarnings("deprecation")
    CertificateProviderInstance deprecatedInstance =
        commonTlsContext.hasTlsCertificateCertificateProviderInstance()
            ? CommonTlsContextUtil.convertDeprecated(
                commonTlsContext.getTlsCertificateCertificateProviderInstance())
            : null;
    return deprecatedInstance;
  }

  @Nullable
  protected static CertificateValidationContext getStaticValidationContext(
      CommonTlsContext commonTlsContext) {
    if (commonTlsContext.hasValidationContext()) {
      return commonTlsContext.getValidationContext();
    } else if (commonTlsContext.hasCombinedValidationContext()) {
      CommonTlsContext.CombinedCertificateValidationContext combinedValidationContext =
          commonTlsContext.getCombinedValidationContext();
      if (combinedValidationContext.hasDefaultValidationContext()) {
        return combinedValidationContext.getDefaultValidationContext();
      }
    }
    return null;
  }

  @Nullable
  protected static CommonTlsContext.CertificateProviderInstance getRootCertProviderInstance(
      CommonTlsContext commonTlsContext) {
    CertificateValidationContext certValidationContext = getStaticValidationContext(
        commonTlsContext);
    if (certValidationContext != null && certValidationContext.hasCaCertificateProviderInstance()) {
      return CommonTlsContextUtil.convert(certValidationContext.getCaCertificateProviderInstance());
    }
    return null;
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

  @Override
  public final void updateSpiffeTrustMap(Map<String, List<X509Certificate>> spiffeTrustMap) {
    savedSpiffeTrustMap = spiffeTrustMap;
    updateSslContextWhenReady();
  }

  private void updateSslContextWhenReady() {
    if (isMtls()) {
      if (savedKey != null && (savedTrustedRoots != null || savedSpiffeTrustMap != null)) {
        updateSslContext();
        clearKeysAndCerts();
      }
    } else if (isRegularTlsAndClientSide()) {
      if (savedTrustedRoots != null || savedSpiffeTrustMap != null) {
        updateSslContext();
        clearKeysAndCerts();
      }
    } else if (isRegularTlsAndServerSide()) {
      if (savedKey != null) {
        updateSslContext();
        clearKeysAndCerts();
      }
    }
  }

  private void clearKeysAndCerts() {
    savedKey = null;
    if (!isUsingSystemRootCerts) {
      savedTrustedRoots = null;
      savedSpiffeTrustMap = null;
    }
    savedCertChain = null;
  }

  protected final boolean isMtls() {
    return certInstance != null && (rootCertInstance != null || isUsingSystemRootCerts);
  }

  protected final boolean isRegularTlsAndClientSide() {
    return (rootCertInstance != null || isUsingSystemRootCerts) && certInstance == null;
  }

  protected final boolean isRegularTlsAndServerSide() {
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

  interface NoExceptionCloseable extends Closeable {
    @Override
    void close();
  }
}
