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

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateProviderPluginInstance;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CombinedCertificateValidationContext;

/** Class for utility functions for {@link CommonTlsContext}. */
public final class CommonTlsContextUtil {

  private CommonTlsContextUtil() {}

  static boolean hasCertProviderInstance(CommonTlsContext commonTlsContext) {
    return commonTlsContext != null
        && (commonTlsContext.hasTlsCertificateCertificateProviderInstance()
            || hasCertProviderValidationContext(commonTlsContext));
  }

  private static boolean hasCertProviderValidationContext(CommonTlsContext commonTlsContext) {
    if (commonTlsContext.hasCombinedValidationContext()) {
      CombinedCertificateValidationContext combinedCertificateValidationContext =
          commonTlsContext.getCombinedValidationContext();
      return combinedCertificateValidationContext.hasValidationContextCertificateProviderInstance();
    }
    return commonTlsContext.hasValidationContextCertificateProviderInstance();
  }

  /**
   * Converts {@link CertificateProviderPluginInstance} to
   * {@link CommonTlsContext.CertificateProviderInstance}.
   */
  public static CommonTlsContext.CertificateProviderInstance convert(
      CertificateProviderPluginInstance pluginInstance) {
    return CommonTlsContext.CertificateProviderInstance.newBuilder()
        .setInstanceName(pluginInstance.getInstanceName())
        .setCertificateName(pluginInstance.getCertificateName()).build();
  }
}
