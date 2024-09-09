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

package io.grpc.s2a.handshaker;

import com.google.common.collect.ImmutableSet;

/** Converts proto messages to Netty strings. */
final class ProtoUtil {
  /**
   * Converts {@link Ciphersuite} to its {@link String} representation.
   *
   * @param ciphersuite the {@link Ciphersuite} to be converted.
   * @return a {@link String} representing the ciphersuite.
   * @throws AssertionError if the {@link Ciphersuite} is not one of the supported ciphersuites.
   */
  static String convertCiphersuite(Ciphersuite ciphersuite) {
    switch (ciphersuite) {
      case CIPHERSUITE_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:
        return "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256";
      case CIPHERSUITE_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:
        return "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384";
      case CIPHERSUITE_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:
        return "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256";
      case CIPHERSUITE_ECDHE_RSA_WITH_AES_128_GCM_SHA256:
        return "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256";
      case CIPHERSUITE_ECDHE_RSA_WITH_AES_256_GCM_SHA384:
        return "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384";
      case CIPHERSUITE_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256:
        return "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256";
      default:
        throw new AssertionError(
            String.format("Ciphersuite %d is not supported.", ciphersuite.getNumber()));
    }
  }

  /**
   * Converts a {@link TLSVersion} object to its {@link String} representation.
   *
   * @param tlsVersion the {@link TLSVersion} object to be converted.
   * @return a {@link String} representation of the TLS version.
   * @throws AssertionError if the {@code tlsVersion} is not one of the supported TLS versions.
   */
  static String convertTlsProtocolVersion(TLSVersion tlsVersion) {
    switch (tlsVersion) {
      case TLS_VERSION_1_3:
        return "TLSv1.3";
      case TLS_VERSION_1_2:
        return "TLSv1.2";
      case TLS_VERSION_1_1:
        return "TLSv1.1";
      case TLS_VERSION_1_0:
        return "TLSv1";
      default:
        throw new AssertionError(
            String.format("TLS version %d is not supported.", tlsVersion.getNumber()));
    }
  }

  /**
   * Builds a set of strings representing all {@link TLSVersion}s between {@code minTlsVersion} and
   * {@code maxTlsVersion}.
   */
  static ImmutableSet<String> buildTlsProtocolVersionSet(
      TLSVersion minTlsVersion, TLSVersion maxTlsVersion) {
    ImmutableSet.Builder<String> tlsVersions = ImmutableSet.<String>builder();
    for (TLSVersion tlsVersion : TLSVersion.values()) {
      int versionNumber;
      try {
        versionNumber = tlsVersion.getNumber();
      } catch (IllegalArgumentException e) {
        continue;
      }
      if (versionNumber < minTlsVersion.getNumber() || versionNumber > maxTlsVersion.getNumber()) {
        continue;
      }
      tlsVersions.add(convertTlsProtocolVersion(tlsVersion));
    }
    return tlsVersions.build();
  }

  private ProtoUtil() {}
}