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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import javax.security.auth.x500.X500Principal;

/**
 * Provides utilities to load, extract, and parse SPIFFE ID according the SPIFFE ID standard.
 * <p>
 * @see <a href="https://github.com/spiffe/spiffe/blob/master/standards/SPIFFE-ID.md">standard</a>
 */
public final class SpiffeUtil {

  private static final Integer URI_SAN_TYPE = 6;

  private SpiffeUtil() {}

  public static Optional<SpiffeId> extractSpiffeId(X509Certificate[] certChain)
      throws CertificateParsingException {
    checkArgument(checkNotNull(certChain, "certChain").length > 0, "CertChain can't be empty");
    Collection<List<?>> subjectAltNames = certChain[0].getSubjectAlternativeNames();
    if (subjectAltNames != null) {
      for (List<?> altName : subjectAltNames) {
        if (URI_SAN_TYPE.equals(altName.get(0))) {
          String uri = (String) altName.get(1);
          // Validation will be plugged in via another PR.
          String[] parts = uri.substring(9).split("/", 2);
          String trustDomain = parts[0];
          String path = parts[1];
          return Optional.of(new SpiffeId(trustDomain, path));
        }
      }
    }
    return Optional.absent();
  }

  public static class SpiffeId {

    private final String trustDomain;
    private final String path;

    private SpiffeId(String trustDomain, String path) {
      this.trustDomain = trustDomain;
      this.path = path;
    }

    public String getTrustDomain() {
      return trustDomain;
    }

    public String getPath() {
      return path;
    }
  }

}