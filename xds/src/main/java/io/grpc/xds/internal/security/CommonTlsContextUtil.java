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

package io.grpc.xds.internal.security;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateProviderPluginInstance;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;

/** Class for utility functions for {@link CommonTlsContext}. */
public final class CommonTlsContextUtil {

  private CommonTlsContextUtil() {}

  public static boolean hasCertProviderInstance(CommonTlsContext commonTlsContext) {
    if (commonTlsContext == null) {
      return false;
    }
    return commonTlsContext.hasTlsCertificateProviderInstance()
        || hasValidationProviderInstance(commonTlsContext);
  }

  private static boolean hasValidationProviderInstance(CommonTlsContext commonTlsContext) {
    if (commonTlsContext.hasValidationContext() && commonTlsContext.getValidationContext()
        .hasCaCertificateProviderInstance()) {
      return true;
    }
    return commonTlsContext.hasCombinedValidationContext()
        && commonTlsContext.getCombinedValidationContext().getDefaultValidationContext()
          .hasCaCertificateProviderInstance();
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

  public static boolean isUsingSystemRootCerts(CommonTlsContext commonTlsContext) {
    if (commonTlsContext.hasCombinedValidationContext()) {
      return commonTlsContext.getCombinedValidationContext().getDefaultValidationContext()
          .hasSystemRootCerts();
    }
    if (commonTlsContext.hasValidationContext()) {
      return commonTlsContext.getValidationContext().hasSystemRootCerts();
    }
    return false;
  }
}
