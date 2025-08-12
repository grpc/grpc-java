/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.xds.internal;

import io.grpc.ChannelCredentials;
import io.grpc.TlsChannelCredentials;
import io.grpc.internal.JsonUtil;
import io.grpc.xds.XdsCredentialsProvider;
import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * A wrapper class that supports {@link TlsChannelCredentials} for Xds
 * by implementing {@link XdsCredentialsProvider}.
 */
public final class TlsXdsCredentialsProvider extends XdsCredentialsProvider {
  private static final String CREDS_NAME = "tls";

  @Override
  protected ChannelCredentials newChannelCredentials(Map<String, ?> jsonConfig) {
    TlsChannelCredentials.Builder builder = TlsChannelCredentials.newBuilder();

    if (jsonConfig == null) {
      return builder.build();
    }

    // use trust certificate file path from bootstrap config if provided; else use system default
    String rootCertPath = JsonUtil.getString(jsonConfig, "ca_certificate_file");
    if (rootCertPath != null) {
      try {
        builder.trustManager(new File(rootCertPath));
      } catch (IOException e) {
        return null;
      }
    }

    // use certificate chain and private key file paths from bootstrap config if provided. Mind that
    // both JSON values must be either set (mTLS case) or both unset (TLS case)
    String certChainPath = JsonUtil.getString(jsonConfig, "certificate_file");
    String privateKeyPath = JsonUtil.getString(jsonConfig, "private_key_file");
    if (certChainPath != null && privateKeyPath != null) {
      try {
        builder.keyManager(new File(certChainPath), new File(privateKeyPath));
      } catch (IOException e) {
        return null;
      }
    } else if (certChainPath != null || privateKeyPath != null) {
      return null;
    }

    // save json config when custom certificate paths were provided in a bootstrap
    if (rootCertPath != null || certChainPath != null) {
      builder.customCertificatesConfig(jsonConfig);
    }
    
    return builder.build();
  }

  @Override
  protected String getName() {
    return CREDS_NAME;
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int priority() {
    return 5;
  }

}
