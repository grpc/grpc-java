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

package io.grpc;

import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;

/**
 * TLS credentials, providing server identity and encryption. Consumers of this credential must
 * verify they understand the configuration via the {@link #incomprehensible incomprehensible()}
 * method. Unless overridden by a {@code Feature}, server identity is provided via {@link
 * #getCertificateChain}, {@link #getPrivateKey}, and {@link #getPrivateKeyPassword}.
 */
public final class TlsServerCredentials extends ServerCredentials {
  /**
   * Creates an instance using provided certificate chain and private key. Generally they should be
   * PEM-encoded and the key is an unencrypted PKCS#8 key (file headers have "BEGIN CERTIFICATE" and
   * "BEGIN PRIVATE KEY").
   */
  public static ServerCredentials create(File certChain, File privateKey) throws IOException {
    return newBuilder().keyManager(certChain, privateKey).build();
  }

  /**
   * Creates an instance using provided certificate chain and private key. Generally they should be
   * PEM-encoded and the key is an unencrypted PKCS#8 key (file headers have "BEGIN CERTIFICATE" and
   * "BEGIN PRIVATE KEY").
   *
   * <p>The streams will not be automatically closed.
   */
  public static ServerCredentials create(
      InputStream certChain, InputStream privateKey) throws IOException {
    return newBuilder().keyManager(certChain, privateKey).build();
  }

  private final boolean fakeFeature;
  private final byte[] certificateChain;
  private final byte[] privateKey;
  private final String privateKeyPassword;
  private final List<KeyManager> keyManagers;
  private final ClientAuth clientAuth;
  private final byte[] rootCertificates;
  private final List<TrustManager> trustManagers;

  TlsServerCredentials(Builder builder) {
    fakeFeature = builder.fakeFeature;
    certificateChain = builder.certificateChain;
    privateKey = builder.privateKey;
    privateKeyPassword = builder.privateKeyPassword;
    keyManagers = builder.keyManagers;
    clientAuth = builder.clientAuth;
    rootCertificates = builder.rootCertificates;
    trustManagers = builder.trustManagers;
  }

  /**
   * The certificate chain for the server's identity, as a new byte array. Generally should be
   * PEM-encoded. If {@code null}, some feature is providing key manager information via a different
   * method.
   */
  public byte[] getCertificateChain() {
    if (certificateChain == null) {
      return null;
    }
    return Arrays.copyOf(certificateChain, certificateChain.length);
  }

  /**
   * The private key for the server's identity, as a new byte array. Generally should be in PKCS#8
   * format. If encrypted, {@link #getPrivateKeyPassword} is the decryption key. If unencrypted, the
   * password will be {@code null}. If {@code null}, some feature is providing key manager
   * information via a different method.
   */
  public byte[] getPrivateKey() {
    if (privateKey == null) {
      return null;
    }
    return Arrays.copyOf(privateKey, privateKey.length);
  }

  /** Returns the password to decrypt the private key, or {@code null} if unencrypted. */
  public String getPrivateKeyPassword() {
    return privateKeyPassword;
  }

  /**
   * Returns the key manager list which provides the server's identity. Entries are scanned checking
   * for specific types, like {@link javax.net.ssl.X509KeyManager}. Only a single entry for a type
   * is used. Entries earlier in the list are higher priority. If {@code null}, key manager
   * information is provided via a different method.
   */
  public List<KeyManager> getKeyManagers() {
    return keyManagers;
  }

  /** Non-{@code null} setting indicating whether the server should expect a client's identity. */
  public ClientAuth getClientAuth() {
    return clientAuth;
  }

  /**
   * Root trust certificates for verifying the client's identity that override the system's
   * defaults. Generally PEM-encoded with multiple certificates concatenated.
   */
  public byte[] getRootCertificates() {
    if (rootCertificates == null) {
      return null;
    }
    return Arrays.copyOf(rootCertificates, rootCertificates.length);
  }

  /**
   * Returns the trust manager list which verifies the client's identity. Entries are scanned
   * checking for specific types, like {@link javax.net.ssl.X509TrustManager}. Only a single entry
   * for a type is used. Entries earlier in the list are higher priority. If {@code null}, trust
   * manager information is provided via the system's default or a different method.
   */
  public List<TrustManager> getTrustManagers() {
    return trustManagers;
  }

  /**
   * Returns an empty set if this credential can be adequately understood via
   * the features listed, otherwise returns a hint of features that are lacking
   * to understand the configuration to be used for manual debugging.
   *
   * <p>An "understood" feature does not imply the caller is able to fully
   * handle the feature. It simply means the caller understands the feature
   * enough to use the appropriate APIs to read the configuration. The caller
   * may support just a subset of a feature, in which case the caller would
   * need to look at the configuration to determine if only the supported
   * subset is used.
   *
   * <p>This method may not be as simple as a set difference. There may be
   * multiple features that can independently satisfy a piece of configuration.
   * If the configuration is incomprehensible, all such features would be
   * returned, even though only one may be necessary.
   *
   * <p>An empty set does not imply that the credentials are fully understood.
   * There may be optional configuration that can be ignored if not understood.
   *
   * <p>Since {@code Feature} is an {@code enum}, {@code understoodFeatures}
   * should generally be an {@link java.util.EnumSet}. {@code
   * understoodFeatures} will not be modified.
   *
   * @param understoodFeatures the features understood by the caller
   * @return empty set if the caller can adequately understand the configuration
   */
  public Set<Feature> incomprehensible(Set<Feature> understoodFeatures) {
    Set<Feature> incomprehensible = EnumSet.noneOf(Feature.class);
    if (fakeFeature) {
      requiredFeature(understoodFeatures, incomprehensible, Feature.FAKE);
    }
    if (clientAuth != ClientAuth.NONE) {
      requiredFeature(understoodFeatures, incomprehensible, Feature.MTLS);
    }
    if (keyManagers != null || trustManagers != null) {
      requiredFeature(understoodFeatures, incomprehensible, Feature.CUSTOM_MANAGERS);
    }
    return Collections.unmodifiableSet(incomprehensible);
  }

  private static void requiredFeature(
      Set<Feature> understoodFeatures, Set<Feature> incomprehensible, Feature feature) {
    if (!understoodFeatures.contains(feature)) {
      incomprehensible.add(feature);
    }
  }

  /**
   * Features to understand TLS configuration. Additional enum values may be added in the future.
   */
  public enum Feature {
    /**
     * A feature that no consumer should understand. It should be used for unit testing to confirm
     * a call to {@link #incomprehensible incomprehensible()} is implemented properly.
     */
    FAKE,
    /**
     * Client certificates may be requested and verified. This feature requires observing {@link
     * #getRootCertificates()} and {@link #getClientAuth()}. The root certificates are used to
     * configure a trust manager for verifying the client's identity. If no root certificates are
     * provided the trust manager will default to the system's root certificates.
     */
    MTLS,
    /**
     * Key managers and trust managers may be specified as {@link KeyManager} and {@link
     * TrustManager} objects. This feature by itself only implies {@link #getKeyManagers()} needs to
     * be observed. But along with {@link #MTLS}, then {@link #getTrustManagers()} needs to be
     * observed as well. When a manager is non-{@code null}, then it is wholly responsible for key
     * or trust material and usage; there is no need to check other manager sources like {@link
     * #getCertificateChain()} or {@link #getPrivateKey()} (if {@code KeyManager} is available), or
     * {@link #getRootCertificates()} (if {@code TrustManager} is available).
     *
     * <p>If other manager sources are available (e.g., {@code getPrivateKey() != null}), then they
     * may be alternative representations of the same configuration and the consumer is free to use
     * those alternative representations if it prefers. But before doing so it <em>must</em> first
     * check that it understands that alternative representation by using {@link #incomprehensible}
     * <em>without</em> the {@code CUSTOM_MANAGERS} feature.
     */
    CUSTOM_MANAGERS,
    ;
  }

  /**
   * Creates a builder for changing default configuration. There is no default key manager, so key
   * material must be specified. The default trust manager uses the system's root certificates. By
   * default no client authentication will occur.
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builder for {@link TlsServerCredentials}. */
  public static final class Builder {
    private boolean fakeFeature;
    private byte[] certificateChain;
    private byte[] privateKey;
    private String privateKeyPassword;
    private List<KeyManager> keyManagers;
    private ClientAuth clientAuth = ClientAuth.NONE;
    private byte[] rootCertificates;
    private List<TrustManager> trustManagers;

    private Builder() {}

    /**
     * Requires {@link Feature#FAKE} to be understood. For use in testing consumers of this
     * credential.
     */
    public Builder requireFakeFeature() {
      fakeFeature = true;
      return this;
    }

    /**
     * Use the provided certificate chain and private key as the server's identity. Generally they
     * should be PEM-encoded and the key is an unencrypted PKCS#8 key (file headers have "BEGIN
     * CERTIFICATE" and "BEGIN PRIVATE KEY").
     */
    public Builder keyManager(File certChain, File privateKey) throws IOException {
      return keyManager(certChain, privateKey, null);
    }

    /**
     * Use the provided certificate chain and possibly-encrypted private key as the server's
     * identity. Generally they should be PEM-encoded and the key is a PKCS#8 key. If the private
     * key is unencrypted, then password must be {@code null}.
     */
    public Builder keyManager(File certChain, File privateKey, String privateKeyPassword)
        throws IOException {
      InputStream certChainIs = new FileInputStream(certChain);
      try {
        InputStream privateKeyIs = new FileInputStream(privateKey);
        try {
          return keyManager(certChainIs, privateKeyIs, privateKeyPassword);
        } finally {
          privateKeyIs.close();
        }
      } finally {
        certChainIs.close();
      }
    }

    /**
     * Use the provided certificate chain and private key as the server's identity. Generally they
     * should be PEM-encoded and the key is an unencrypted PKCS#8 key (file headers have "BEGIN
     * CERTIFICATE" and "BEGIN PRIVATE KEY").
     */
    public Builder keyManager(InputStream certChain, InputStream privateKey) throws IOException {
      return keyManager(certChain, privateKey, null);
    }

    /**
     * Use the provided certificate chain and possibly-encrypted private key as the server's
     * identity. Generally they should be PEM-encoded and the key is a PKCS#8 key. If the private
     * key is unencrypted, then password must be {@code null}.
     */
    public Builder keyManager(
        InputStream certChain, InputStream privateKey, String privateKeyPassword)
        throws IOException {
      byte[] certChainBytes = ByteStreams.toByteArray(certChain);
      byte[] privateKeyBytes = ByteStreams.toByteArray(privateKey);
      clearKeyManagers();
      this.certificateChain = certChainBytes;
      this.privateKey = privateKeyBytes;
      this.privateKeyPassword = privateKeyPassword;
      return this;
    }

    /**
     * Have the provided key manager select the server's identity. Although multiple are allowed,
     * only the first instance implementing a particular interface is used. So generally there will
     * just be a single entry and it implements {@link javax.net.ssl.X509KeyManager}.
     */
    public Builder keyManager(KeyManager... keyManagers) {
      List<KeyManager> keyManagerList = Collections.unmodifiableList(new ArrayList<>(
          Arrays.asList(keyManagers)));
      clearKeyManagers();
      this.keyManagers = keyManagerList;
      return this;
    }

    private void clearKeyManagers() {
      this.certificateChain = null;
      this.privateKey = null;
      this.privateKeyPassword = null;
      this.keyManagers = null;
    }

    /**
     * Indicates whether the server should expect a client's identity. Must not be {@code null}.
     * Defaults to {@link ClientAuth#NONE}.
     */
    public Builder clientAuth(ClientAuth clientAuth) {
      Preconditions.checkNotNull(clientAuth, "clientAuth");
      this.clientAuth = clientAuth;
      return this;
    }

    /**
     * Use the provided root certificates to verify the client's identity instead of the system's
     * default. Generally they should be PEM-encoded with all the certificates concatenated together
     * (file header has "BEGIN CERTIFICATE", and would occur once per certificate).
     */
    public Builder trustManager(File rootCerts) throws IOException {
      InputStream rootCertsIs = new FileInputStream(rootCerts);
      try {
        return trustManager(rootCertsIs);
      } finally {
        rootCertsIs.close();
      }
    }

    /**
     * Use the provided root certificates to verify the client's identity instead of the system's
     * default. Generally they should be PEM-encoded with all the certificates concatenated together
     * (file header has "BEGIN CERTIFICATE", and would occur once per certificate).
     */
    public Builder trustManager(InputStream rootCerts) throws IOException {
      byte[] rootCertsBytes = ByteStreams.toByteArray(rootCerts);
      clearTrustManagers();
      this.rootCertificates = rootCertsBytes;
      return this;
    }

    /**
     * Have the provided trust manager verify the client's identity instead of the system's default.
     * Although multiple are allowed, only the first instance implementing a particular interface is
     * used. So generally there will just be a single entry and it implements {@link
     * javax.net.ssl.X509TrustManager}.
     */
    public Builder trustManager(TrustManager... trustManagers) {
      List<TrustManager> trustManagerList = Collections.unmodifiableList(new ArrayList<>(
          Arrays.asList(trustManagers)));
      clearTrustManagers();
      this.trustManagers = trustManagerList;
      return this;
    }

    private void clearTrustManagers() {
      this.rootCertificates = null;
      this.trustManagers = null;
    }

    /** Construct the credentials. */
    public ServerCredentials build() {
      if (certificateChain == null && keyManagers == null) {
        throw new IllegalStateException("A key manager is required");
      }
      return new TlsServerCredentials(this);
    }
  }

  /** The level of authentication the server should expect from the client. */
  public enum ClientAuth {
    /** Clients will not present any identity. */
    NONE,

    /**
     * Clients are requested to present their identity, but clients without identities are
     * permitted.
     */
    OPTIONAL,

    /**
     * Clients are requested to present their identity, and are required to provide a valid
     * identity.
     */
    REQUIRE;
  }
}
