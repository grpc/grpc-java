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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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

/**
 * Will be merged with SpiffeUtil.
 * Provides utilities to load, extract, and parse SPIFFE ID according the SPIFFE ID standard.
 *
 */
public final class SpiffeUtil2 {

  private static final Logger log = Logger.getLogger(SpiffeUtil2.class.getName());

  private static final Integer URI_SAN_TYPE = 6;
  private static final String USE_PARAMETER_VALUE = "x509-svid";
  private static final String KTY_PARAMETER_VALUE = "RSA";

  /**
   * Parses a leaf certificate from the chain to extract unique SPIFFE ID. In case of success,
   * returns parsed TrustDomain and Path.
   *
   * @param certChain certificate chain to extract SPIFFE ID from
   */
  public static Optional<SpiffeId> extractSpiffeId(X509Certificate[] certChain)
      throws CertificateParsingException {
    checkArgument(checkNotNull(certChain, "certChain").length > 0, "CertChain can't be empty");
    Collection<List<?>> subjectAltNames = certChain[0].getSubjectAlternativeNames();
    if (subjectAltNames != null) {
      boolean spiffeFound = false;
      for (List<?> altName : subjectAltNames) {
        if (altName.size() == 0) {
          continue;
        }
        if (URI_SAN_TYPE.equals(altName.get(0))) {
          if (spiffeFound) {
            throw new IllegalArgumentException("Multiple URI SAN values found in the leaf cert.");
          }
          spiffeFound = true;
        }
      }
      if (!spiffeFound) {
        return Optional.absent();
      }
      for (List<?> altName : subjectAltNames) {
        if (altName.size() < 2) {
          continue;
        }
        if (URI_SAN_TYPE.equals(altName.get(0))) {
          String uri = (String) altName.get(1);
          // Real validation will be plugged in via another PR (SpiffeUtil).
          String[] parts = uri.substring(9).split("/", 2);
          String trustDomain = parts[0];
          String path = parts[1];
          return Optional.of(new SpiffeId(trustDomain, path));
        }
      }
    }
    return Optional.absent();
  }

  /**
  * Loads a SPIFFE trust bundle from a file, parsing it from the JSON format.
  * In case of success, returns trust domains, their associated sequence numbers, and X.509
  * certificates.
  *
  * @param trustBundleFile the file path to the JSON file containing the trust bundle
  * @see <a href="https://github.com/spiffe/spiffe/blob/main/standards/SPIFFE_Trust_Domain_and_Bundle.md">JSON format</a>
  * @see <a href="https://github.com/spiffe/spiffe/blob/main/standards/X509-SVID.md#61-publishing-spiffe-bundle-elements">JWK entry format</a>
  */
  public static SpiffeBundle loadTrustBundleFromFile(String trustBundleFile) throws IOException {
    Map<String, ?> trustDomainsNode = readTrustDomainsFromFile(trustBundleFile);
    Map<String, List<X509Certificate>> trustBundleMap = new HashMap<>();
    Map<String, Long> sequenceNumbers = new HashMap<>();
    for (String trustDomainName : trustDomainsNode.keySet()) {
      Map<String, ?> domainNode = JsonUtil.getObject(trustDomainsNode, trustDomainName);
      if (domainNode == null || domainNode.size() == 0) {
        trustBundleMap.put(trustDomainName, Collections.emptyList());
        continue;
      }
      Long sequenceNumber = JsonUtil.getNumberAsLong(domainNode, "sequence_number");
      sequenceNumbers.put(trustDomainName, sequenceNumber == null ? -1L : sequenceNumber);
      List<Map<String, ?>> keysNode = JsonUtil.getListOfObjects(domainNode, "keys");
      if (keysNode == null || keysNode.size() == 0) {
        trustBundleMap.put(trustDomainName, Collections.emptyList());
        continue;
      }
      trustBundleMap.put(trustDomainName, extractCert(keysNode, trustDomainName));
    }
    return new SpiffeBundle(sequenceNumbers, trustBundleMap);
  }

  private static Map<String, ?> readTrustDomainsFromFile(String filePath) throws IOException {
    Path path = Paths.get(checkNotNull(filePath, "trustBundleFile"));
    String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    Object jsonObject = JsonParser.parseNoDuplicates(json);
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
    return trustDomainsNode;
  }

  private static boolean checkJwkEntry(Map<String, ?> jwkNode, String trustDomainName) {
    String kty = JsonUtil.getString(jwkNode, "kty");
    if (kty == null || !kty.equals(KTY_PARAMETER_VALUE)) {
      log.log(Level.SEVERE, String.format("'kty' parameter must be '%s' but '%s' found. "
              + "Skipping certificate loading for trust domain '%s'.", KTY_PARAMETER_VALUE, kty,
          trustDomainName));
      return false;
    }
    String kid = JsonUtil.getString(jwkNode, "kid");
    if (kid != null && !kid.equals("")) {
      log.log(Level.SEVERE, String.format("'kid' parameter must not be set but value '%s' "
              + "found. Skipping certificate loading for trust domain '%s'.", kid,
          trustDomainName));
      return false;
    }
    String use = JsonUtil.getString(jwkNode, "use");
    if (use == null || !use.equals(USE_PARAMETER_VALUE)) {
      log.log(Level.SEVERE, String.format("'use' parameter must be '%s' but '%s' found. "
              + "Skipping certificate loading for trust domain '%s'.", USE_PARAMETER_VALUE, use,
          trustDomainName));
      return false;
    }
    return true;
  }

  private static List<X509Certificate> extractCert(List<Map<String, ?>> keysNode,
      String trustDomainName) {
    List<X509Certificate> result = new ArrayList<>();
    for (Map<String, ?> keyNode : keysNode) {
      if (!checkJwkEntry(keyNode, trustDomainName)) {
        break;
      }
      String rawCert = JsonUtil.getString(keyNode, "x5c");
      if (rawCert == null) {
        break;
      }
      InputStream stream = new ByteArrayInputStream(rawCert.getBytes(StandardCharsets.UTF_8));
      try {
        Collection<? extends Certificate> certs = CertificateFactory.getInstance("X509")
            .generateCertificates(stream);
        if (certs.size() != 1) {
          log.log(Level.SEVERE, String.format("Exactly 1 certificate is expected, but %s found for "
                  + "domain %s.", certs.size(), trustDomainName));
        } else {
          result.add(certs.toArray(new X509Certificate[0])[0]);
        }
      } catch (CertificateException e) {
        log.log(Level.SEVERE, String.format("Certificate for domain %s can't be parsed.",
            trustDomainName), e);
      }
    }
    return result;
  }

  // Will be merged with other PR
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

  /**
   * Represents a Trust Bundle inspired by SPIFFE Bundle Format standard. Only trust domain's
   * sequence numbers and x509 certificates are supported.
   * @see <a href="https://github.com/spiffe/spiffe/blob/main/standards/SPIFFE_Trust_Domain_and_Bundle.md#4-spiffe-bundle-format">Standard</a>
   */
  public static final class SpiffeBundle {

    private final ImmutableMap<String, Long> sequenceNumbers;

    private final ImmutableMap<String, ImmutableList<X509Certificate>> bundleMap;

    public SpiffeBundle(Map<String, Long> sequenceNumbers,
        Map<String, List<X509Certificate>> trustDomainMap) {
      this.sequenceNumbers = ImmutableMap.copyOf(sequenceNumbers);
      ImmutableMap.Builder<String, ImmutableList<X509Certificate>> builder = ImmutableMap.builder();
      for (Map.Entry<String, List<X509Certificate>> entry : trustDomainMap.entrySet()) {
        builder.put(entry.getKey(), ImmutableList.copyOf(entry.getValue()));
      }
      this.bundleMap = builder.build();
    }

    public ImmutableMap<String, Long> getSequenceNumbers() {
      return sequenceNumbers;
    }

    public ImmutableMap<String, ImmutableList<X509Certificate>> getBundleMap() {
      return bundleMap;
    }
  }
}