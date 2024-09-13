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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.x500.X500Principal;

/**
 * Provides utilities to load, extract, and parse SPIFFE ID according the SPIFFE ID standard.
 * <p>
 * @see <a href="https://github.com/spiffe/spiffe/blob/master/standards/SPIFFE-ID.md">standard</a>
 */
public final class SpiffeUtil {

  private static final Logger log = Logger.getLogger(SpiffeUtil.class.getName());

  private static final Integer URI_SAN_TYPE = 6;
  private static final String USE_PARAMETER_VALUE = "x509-svid";

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

  public static TrustBundle loadTrustBundleFromFile(String trustBundleFile) throws IOException {
    Path path = Paths.get(checkNotNull(trustBundleFile, "trustBundleFile"));
    String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    Object jsonObject = JsonParser.parse(json);
    if (!(jsonObject instanceof Map)) {
      throw new IllegalArgumentException(
          "SPIFFE Trust Bundle should be a JSON object. Found: "
              + (jsonObject == null ? null : jsonObject.getClass()));
    }
    @SuppressWarnings("unchecked")
    Map<String, ?> root = (Map<String, ?>)jsonObject;
    Map<String, ?> trustDomainsNode = JsonUtil.getObject(root, "trust_domains");
    checkArgument(checkNotNull(trustDomainsNode,
        "Mandatory trust_domains element is missing").size() > 0,
        "Mandatory trust_domains element is missing");

    Map<String, List<X509Certificate>> trustBundleMap = new HashMap<>();
    Map<String, Long> sequenceNumbers = new HashMap<>();
    for (String trustDomainName : trustDomainsNode.keySet()) {
      Map<String, ?> domainNode = JsonUtil.getObject(trustDomainsNode, trustDomainName);
      if (domainNode == null || domainNode.size() == 0) {
        trustBundleMap.put(trustDomainName, Collections.emptyList());
        continue;
      }
      Long sequenceNumber = JsonUtil.getNumberAsLong(domainNode, "sequence_number");
      sequenceNumbers.put(trustDomainName, sequenceNumber);
      List<Map<String, ?>> keysNode = JsonUtil.getListOfObjects(domainNode, "keys");
      if (keysNode == null || keysNode.size() == 0) {
        trustBundleMap.put(trustDomainName, Collections.emptyList());
        continue;
      }
      List<X509Certificate> roots = new ArrayList<>();
      for (Map<String, ?> keyNode : keysNode) {
        String kid = JsonUtil.getString(keyNode, "kid");
        if (kid != null && !kid.equals("")) {
          log.log(Level.SEVERE, String.format("'kid' parameter must not be set but value '%s' "
              + "found. Skipping certificate loading for trust domain '%s'.", kid,
              trustDomainName));
          break;
        }
        String use = JsonUtil.getString(keyNode, "use");
        if (use == null || !use.equals(USE_PARAMETER_VALUE)) {
          log.log(Level.SEVERE, String.format("'use' parameter must be '%s' but '%s' found. "
              + "Skipping certificate loading for trust domain '%s'.", USE_PARAMETER_VALUE, use,
              trustDomainName));
          break;
        }
        String rawCert = JsonUtil.getString(keyNode, "x5c");
        if (rawCert == null) {
          break;
        }
        InputStream stream = new ByteArrayInputStream(rawCert.getBytes(StandardCharsets.UTF_8));
        try {
          Collection<? extends Certificate> certs = CertificateFactory.getInstance("X509").
              generateCertificates(stream);
          if (certs.size() > 0){
            roots.add(certs.toArray(new X509Certificate[0])[0]);
          }
        } catch (CertificateException e) {
          log.log(Level.SEVERE, String.format("Certificate for domain %s can't be parsed.",
              trustDomainName), e);
        }
      }
      trustBundleMap.put(trustDomainName, roots);
    }
    return new TrustBundle(sequenceNumbers, trustBundleMap);
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

  public static class TrustBundle {

    private final Map<String, Long> sequenceNumbers;

    private final Map<String, List<X509Certificate>> trustBundleMap;

    public TrustBundle(Map<String, Long> sequenceNumbers, Map<String, List<X509Certificate>> trustDomainMap) {
      this.sequenceNumbers = sequenceNumbers;
      this.trustBundleMap = trustDomainMap;
    }

    public Map<String, Long> getSequenceNumbers() {
      return sequenceNumbers;
    }

    public Map<String, List<X509Certificate>> getTrustBundleMap() {
      return trustBundleMap;
    }
  }
}