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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;

/** Converts proto messages to Netty strings. */
final class ProtoUtil {

  /**
   * Converts a {@link TLSVersion} object to its {@link String} representation.
   *
   * @param tlsVersion the {@link TLSVersion} object to be converted.
   * @return a {@link String} representation of the TLS version.
   * @throws IllegalArgumentException if the {@code tlsVersion} is not one of
   *     the supported TLS versions.
   */
  @VisibleForTesting
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
        throw new IllegalArgumentException(
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
      if (versionNumber >= minTlsVersion.getNumber()
          && versionNumber <= maxTlsVersion.getNumber()) {
        try {
          tlsVersions.add(convertTlsProtocolVersion(tlsVersion));
        } catch (IllegalArgumentException e) {
          continue;
        }
      }
    }
    return tlsVersions.build();
  }

  private ProtoUtil() {}
}