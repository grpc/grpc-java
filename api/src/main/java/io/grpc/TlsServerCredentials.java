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

import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * TLS credentials, providing server identity and encryption. Consumers of this credential must
 * verify they understand the configuration via the {@link #incomprehensible incomprehensible()}
 * method. Unless overridden by a {@code Feature}, server identity is provided via {@link
 * #getCertificateChain}, {@link #getPrivateKey}, and {@link #getPrivateKeyPassword}.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7621")
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

  TlsServerCredentials(Builder builder) {
    fakeFeature = builder.fakeFeature;
    certificateChain = builder.certificateChain;
    privateKey = builder.privateKey;
    privateKeyPassword = builder.privateKeyPassword;
  }

  /**
   * The certificate chain, as a new byte array. Generally should be PEM-encoded.
   */
  public byte[] getCertificateChain() {
    return Arrays.copyOf(certificateChain, certificateChain.length);
  }

  /**
   * The private key, as a new byte array. Generally should be in PKCS#8 format. If encrypted,
   * {@link #getPrivateKeyPassword} is the decryption key. If unencrypted, the password will be
   * {@code null}.
   */
  public byte[] getPrivateKey() {
    return Arrays.copyOf(privateKey, privateKey.length);
  }

  /** Returns the password to decrypt the private key, or {@code null} if unencrypted. */
  public String getPrivateKeyPassword() {
    return privateKeyPassword;
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
    ;
  }

  /** Creates a builder for changing default configuration. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builder for {@link TlsServerCredentials}. */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/7621")
  public static final class Builder {
    private boolean fakeFeature;
    private byte[] certificateChain;
    private byte[] privateKey;
    private String privateKeyPassword;

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
     * Creates an instance using provided certificate chain and private key. Generally they should
     * be PEM-encoded and the key is an unencrypted PKCS#8 key (file headers have "BEGIN
     * CERTIFICATE" and "BEGIN PRIVATE KEY").
     */
    public Builder keyManager(File certChain, File privateKey) throws IOException {
      return keyManager(certChain, privateKey, null);
    }

    /**
     * Creates an instance using provided certificate chain and possibly-encrypted private key.
     * Generally they should be PEM-encoded and the key is a PKCS#8 key. If the private key is
     * unencrypted, then password must be {@code null}.
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
     * Creates an instance using provided certificate chain and private key. Generally they should
     * be PEM-encoded and the key is an unencrypted PKCS#8 key (file headers have "BEGIN
     * CERTIFICATE" and "BEGIN PRIVATE KEY").
     */
    public Builder keyManager(InputStream certChain, InputStream privateKey) throws IOException {
      return keyManager(certChain, privateKey, null);
    }

    /**
     * Creates an instance using provided certificate chain and possibly-encrypted private key.
     * Generally they should be PEM-encoded and the key is a PKCS#8 key. If the private key is
     * unencrypted, then password must be {@code null}.
     */
    public Builder keyManager(
        InputStream certChain, InputStream privateKey, String privateKeyPassword)
        throws IOException {
      byte[] certChainBytes = ByteStreams.toByteArray(certChain);
      byte[] privateKeyBytes = ByteStreams.toByteArray(privateKey);
      this.certificateChain = certChainBytes;
      this.privateKey = privateKeyBytes;
      this.privateKeyPassword = privateKeyPassword;
      return this;
    }

    /** Construct the credentials. */
    public ServerCredentials build() {
      if (certificateChain == null) {
        throw new IllegalStateException("A key manager is required");
      }
      return new TlsServerCredentials(this);
    }
  }
}
