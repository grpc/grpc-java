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
import io.grpc.util.AdvancedTlsX509KeyManager;
import io.grpc.util.AdvancedTlsX509TrustManager;
import io.grpc.xds.XdsCredentialsProvider;
import java.io.File;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A wrapper class that supports {@link TlsChannelCredentials} for Xds
 * by implementing {@link XdsCredentialsProvider}.
 */
public final class TlsXdsCredentialsProvider extends XdsCredentialsProvider {
  private static final Logger logger = Logger.getLogger(TlsXdsCredentialsProvider.class.getName());
  private static final String CREDS_NAME = "tls";
  private static final String CERT_FILE_KEY = "certificate_file";
  private static final String KEY_FILE_KEY = "private_key_file";
  private static final String ROOT_FILE_KEY = "ca_certificate_file";

  @Override
  protected ChannelCredentials newChannelCredentials(Map<String, ?> jsonConfig) {
    TlsChannelCredentials.Builder builder = TlsChannelCredentials.newBuilder();

    if (jsonConfig == null) {
      return builder.build();
    }

    // use trust certificate file path from bootstrap config if provided; else use system default
    String rootCertPath = JsonUtil.getString(jsonConfig, ROOT_FILE_KEY);
    if (rootCertPath != null) {
      try {
        AdvancedTlsX509TrustManager trustManager = AdvancedTlsX509TrustManager.newBuilder().build();
        trustManager.updateTrustCredentials(new File(rootCertPath));
        builder.trustManager(trustManager);
      } catch (Exception e) {
        logger.log(Level.WARNING, "Unable to read root certificates", e);
        return null;
      }
    }

    // use certificate chain and private key file paths from bootstrap config if provided. Mind that
    // both JSON values must be either set (mTLS case) or both unset (TLS case)
    String certChainPath = JsonUtil.getString(jsonConfig, CERT_FILE_KEY);
    String privateKeyPath = JsonUtil.getString(jsonConfig, KEY_FILE_KEY);
    if (certChainPath != null && privateKeyPath != null) {
      try {
        AdvancedTlsX509KeyManager keyManager = new AdvancedTlsX509KeyManager();
        keyManager.updateIdentityCredentials(new File(certChainPath), new File(privateKeyPath));
        builder.keyManager(keyManager);
      } catch (Exception e) {
        logger.log(Level.WARNING, "Unable to read certificate chain or private key", e);
        return null;
      }
    } else if (certChainPath != null || privateKeyPath != null) {
      logger.log(Level.WARNING, "Certificate chain and private key must be both set or unset");
      return null;
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
