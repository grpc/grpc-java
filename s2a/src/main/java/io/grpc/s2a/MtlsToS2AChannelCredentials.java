/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.s2a;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;

import io.grpc.ChannelCredentials;
import io.grpc.TlsChannelCredentials;
import io.grpc.util.AdvancedTlsX509KeyManager;
import io.grpc.util.AdvancedTlsX509TrustManager;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Configures an {@code S2AChannelCredentials.Builder} instance with credentials used to establish a
 * connection with the S2A to support talking to the S2A over mTLS.
 */
public final class MtlsToS2AChannelCredentials {
  /**
   * Creates a {@code S2AChannelCredentials.Builder} builder, that talks to the S2A over mTLS.
   *
   * @param s2aAddress the address of the S2A server used to secure the connection.
   * @param privateKeyPath the path to the private key PEM to use for authenticating to the S2A.
   * @param certChainPath the path to the cert chain PEM to use for authenticating to the S2A.
   * @param trustBundlePath the path to the trust bundle PEM.
   * @return a {@code MtlsToS2AChannelCredentials.Builder} instance.
   */
  public static Builder createBuilder(
      String s2aAddress, String privateKeyPath, String certChainPath, String trustBundlePath) {
    checkArgument(!isNullOrEmpty(s2aAddress), "S2A address must not be null or empty.");
    checkArgument(!isNullOrEmpty(privateKeyPath), "privateKeyPath must not be null or empty.");
    checkArgument(!isNullOrEmpty(certChainPath), "certChainPath must not be null or empty.");
    checkArgument(!isNullOrEmpty(trustBundlePath), "trustBundlePath must not be null or empty.");
    return new Builder(s2aAddress, privateKeyPath, certChainPath, trustBundlePath);
  }

  /** Builds an {@code MtlsToS2AChannelCredentials} instance. */
  public static final class Builder {
    private final String s2aAddress;
    private final String privateKeyPath;
    private final String certChainPath;
    private final String trustBundlePath;

    Builder(
        String s2aAddress, String privateKeyPath, String certChainPath, String trustBundlePath) {
      this.s2aAddress = s2aAddress;
      this.privateKeyPath = privateKeyPath;
      this.certChainPath = certChainPath;
      this.trustBundlePath = trustBundlePath;
    }

    public S2AChannelCredentials.Builder build() throws GeneralSecurityException, IOException {
      checkState(!isNullOrEmpty(s2aAddress), "S2A address must not be null or empty.");
      checkState(!isNullOrEmpty(privateKeyPath), "privateKeyPath must not be null or empty.");
      checkState(!isNullOrEmpty(certChainPath), "certChainPath must not be null or empty.");
      checkState(!isNullOrEmpty(trustBundlePath), "trustBundlePath must not be null or empty.");
      File privateKeyFile = new File(privateKeyPath);
      File certChainFile = new File(certChainPath);
      File trustBundleFile = new File(trustBundlePath);

      AdvancedTlsX509KeyManager keyManager = new AdvancedTlsX509KeyManager();
      keyManager.updateIdentityCredentials(certChainFile, privateKeyFile);

      AdvancedTlsX509TrustManager trustManager = AdvancedTlsX509TrustManager.newBuilder().build();
      trustManager.updateTrustCredentials(trustBundleFile);

      ChannelCredentials channelToS2ACredentials =
          TlsChannelCredentials.newBuilder()
              .keyManager(keyManager)
              .trustManager(trustManager)
              .build();

      return S2AChannelCredentials.createBuilder(s2aAddress)
          .setS2AChannelCredentials(channelToS2ACredentials);
    }
  }

  private MtlsToS2AChannelCredentials() {}
}