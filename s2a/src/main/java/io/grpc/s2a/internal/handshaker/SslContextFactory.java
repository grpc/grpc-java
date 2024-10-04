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

package io.grpc.s2a.internal.handshaker;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.s2a.internal.handshaker.S2AIdentity;
import io.netty.handler.ssl.OpenSslContextOption;
import io.netty.handler.ssl.OpenSslSessionContext;
import io.netty.handler.ssl.OpenSslX509KeyManagerFactory;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Optional;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLSessionContext;

/** Creates {@link SslContext} objects with TLS configurations from S2A server. */
final class SslContextFactory {

  /**
   * Creates {@link SslContext} objects for client with TLS configurations from S2A server.
   *
   * @param stub the {@link S2AStub} to talk to the S2A server.
   * @param targetName the {@link String} of the server that this client makes connection to.
   * @param localIdentity the {@link S2AIdentity} that should be used when talking to S2A server.
   *     Will use default identity if empty.
   * @return a {@link SslContext} object.
   * @throws NullPointerException if either {@code stub} or {@code targetName} is null.
   * @throws IOException if an unexpected response from S2A server is received.
   * @throws InterruptedException if {@code stub} is closed.
   */
  static SslContext createForClient(
      S2AStub stub, String targetName, Optional<S2AIdentity> localIdentity)
      throws IOException,
          InterruptedException,
          CertificateException,
          KeyStoreException,
          NoSuchAlgorithmException,
          UnrecoverableKeyException,
          GeneralSecurityException {
    checkNotNull(stub, "stub should not be null.");
    checkNotNull(targetName, "targetName should not be null on client side.");
    GetTlsConfigurationResp.ClientTlsConfiguration clientTlsConfiguration;
    try {
      clientTlsConfiguration = getClientTlsConfigurationFromS2A(stub, localIdentity);
    } catch (IOException | InterruptedException e) {
      throw new GeneralSecurityException("Failed to get client TLS configuration from S2A.", e);
    }

    // Use the default value for timeout.
    // Use the smallest possible value for cache size.
    // The Provider is by default OPENSSL. No need to manually set it.
    SslContextBuilder sslContextBuilder =
        GrpcSslContexts.configure(SslContextBuilder.forClient())
            .sessionCacheSize(1)
            .sessionTimeout(0);

    configureSslContextWithClientTlsConfiguration(clientTlsConfiguration, sslContextBuilder);
    sslContextBuilder.trustManager(
        S2ATrustManager.createForClient(stub, targetName, localIdentity));
    sslContextBuilder.option(
        OpenSslContextOption.PRIVATE_KEY_METHOD, S2APrivateKeyMethod.create(stub, localIdentity));

    SslContext sslContext = sslContextBuilder.build();
    SSLSessionContext sslSessionContext = sslContext.sessionContext();
    if (sslSessionContext instanceof OpenSslSessionContext) {
      OpenSslSessionContext openSslSessionContext = (OpenSslSessionContext) sslSessionContext;
      openSslSessionContext.setSessionCacheEnabled(false);
    }

    return sslContext;
  }

  private static GetTlsConfigurationResp.ClientTlsConfiguration getClientTlsConfigurationFromS2A(
      S2AStub stub, Optional<S2AIdentity> localIdentity) throws IOException, InterruptedException {
    checkNotNull(stub, "stub should not be null.");
    SessionReq.Builder reqBuilder = SessionReq.newBuilder();
    if (localIdentity.isPresent()) {
      reqBuilder.setLocalIdentity(localIdentity.get().getIdentity());
    }
    Optional<AuthenticationMechanism> authMechanism =
        GetAuthenticationMechanisms.getAuthMechanism(localIdentity);
    if (authMechanism.isPresent()) {
      reqBuilder.addAuthenticationMechanisms(authMechanism.get());
    }
    SessionResp resp =
        stub.send(
            reqBuilder
                .setGetTlsConfigurationReq(
                    GetTlsConfigurationReq.newBuilder()
                        .setConnectionSide(ConnectionSide.CONNECTION_SIDE_CLIENT))
                .build());
    if (resp.hasStatus() && resp.getStatus().getCode() != 0) {
      throw new S2AConnectionException(
          String.format(
              "response from S2A server has ean error %d with error message %s.",
              resp.getStatus().getCode(), resp.getStatus().getDetails()));
    }
    if (!resp.getGetTlsConfigurationResp().hasClientTlsConfiguration()) {
      throw new S2AConnectionException(
          "Response from S2A server does NOT contain ClientTlsConfiguration.");
    }
    return resp.getGetTlsConfigurationResp().getClientTlsConfiguration();
  }

  private static void configureSslContextWithClientTlsConfiguration(
      GetTlsConfigurationResp.ClientTlsConfiguration clientTlsConfiguration,
      SslContextBuilder sslContextBuilder)
      throws CertificateException,
          IOException,
          KeyStoreException,
          NoSuchAlgorithmException,
          UnrecoverableKeyException {
    sslContextBuilder.keyManager(createKeylessManager(clientTlsConfiguration));
    ImmutableSet<String> tlsVersions;
    tlsVersions =
        ProtoUtil.buildTlsProtocolVersionSet(
            clientTlsConfiguration.getMinTlsVersion(), clientTlsConfiguration.getMaxTlsVersion());
    if (tlsVersions.isEmpty()) {
      throw new S2AConnectionException("Set of TLS versions received from S2A server is"
        + " empty or not supported.");
    }
    sslContextBuilder.protocols(tlsVersions);
  }

  private static KeyManager createKeylessManager(
      GetTlsConfigurationResp.ClientTlsConfiguration clientTlsConfiguration)
      throws CertificateException,
          IOException,
          KeyStoreException,
          NoSuchAlgorithmException,
          UnrecoverableKeyException {
    X509Certificate[] certificates =
        new X509Certificate[clientTlsConfiguration.getCertificateChainCount()];
    for (int i = 0; i < clientTlsConfiguration.getCertificateChainCount(); ++i) {
      certificates[i] = convertStringToX509Cert(clientTlsConfiguration.getCertificateChain(i));
    }
    KeyManager[] keyManagers =
        OpenSslX509KeyManagerFactory.newKeyless(certificates).getKeyManagers();
    if (keyManagers == null || keyManagers.length == 0) {
      throw new IllegalStateException("No key managers created.");
    }
    return keyManagers[0];
  }

  private static X509Certificate convertStringToX509Cert(String certificate)
      throws CertificateException {
    return (X509Certificate)
        CertificateFactory.getInstance("X509")
            .generateCertificate(new ByteArrayInputStream(certificate.getBytes(UTF_8)));
  }

  private SslContextFactory() {}
}