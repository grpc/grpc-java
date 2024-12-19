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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import java.util.Locale;
import java.util.Map;

/**
 * Provides utilities to manage SPIFFE bundles, extract SPIFFE IDs from X.509 certificate chains,
 * and parse SPIFFE IDs.
 * @see <a href="https://github.com/spiffe/spiffe/blob/master/standards/SPIFFE-ID.md">Standard</a>
 */
public final class SpiffeUtil {

  private static final Integer URI_SAN_TYPE = 6;
  private static final String USE_PARAMETER_VALUE = "x509-svid";
  private static final String KTY_PARAMETER_VALUE = "RSA";
  private static final String CERTIFICATE_PREFIX = "-----BEGIN CERTIFICATE-----\n";
  private static final String CERTIFICATE_SUFFIX = "-----END CERTIFICATE-----";
  private static final String PREFIX = "spiffe://";

  private SpiffeUtil() {}

  /**
   * Parses a URI string, applies validation rules described in SPIFFE standard, and, in case of
   * success, returns parsed TrustDomain and Path.
   *
   * @param uri a String representing a SPIFFE ID
   */
  public static SpiffeId parse(String uri) {
    doInitialUriValidation(uri);
    checkArgument(uri.toLowerCase(Locale.US).startsWith(PREFIX), "Spiffe Id must start with "
        + PREFIX);
    String domainAndPath = uri.substring(PREFIX.length());
    String trustDomain;
    String path;
    if (!domainAndPath.contains("/")) {
      trustDomain = domainAndPath;
      path =  "";
    } else {
      String[] parts = domainAndPath.split("/", 2);
      trustDomain = parts[0];
      path = parts[1];
      checkArgument(!path.isEmpty(), "Path must not include a trailing '/'");
    }
    validateTrustDomain(trustDomain);
    validatePath(path);
    if (!path.isEmpty()) {
      path = "/" + path;
    }
    return new SpiffeId(trustDomain, path);
  }

  private static void doInitialUriValidation(String uri) {
    checkArgument(checkNotNull(uri, "uri").length() > 0, "Spiffe Id can't be empty");
    checkArgument(uri.length() <= 2048, "Spiffe Id maximum length is 2048 characters");
    checkArgument(!uri.contains("#"), "Spiffe Id must not contain query fragments");
    checkArgument(!uri.contains("?"), "Spiffe Id must not contain query parameters");
  }

  private static void validateTrustDomain(String trustDomain) {
    checkArgument(!trustDomain.isEmpty(), "Trust Domain can't be empty");
    checkArgument(trustDomain.length() < 256, "Trust Domain maximum length is 255 characters");
    checkArgument(trustDomain.matches("[a-z0-9._-]+"),
        "Trust Domain must contain only letters, numbers, dots, dashes, and underscores"
            + " ([a-z0-9.-_])");
  }

  private static void validatePath(String path) {
    if (path.isEmpty()) {
      return;
    }
    checkArgument(!path.endsWith("/"), "Path must not include a trailing '/'");
    for (String segment : Splitter.on("/").split(path)) {
      validatePathSegment(segment);
    }
  }

  private static void validatePathSegment(String pathSegment) {
    checkArgument(!pathSegment.isEmpty(), "Individual path segments must not be empty");
    checkArgument(!(pathSegment.equals(".") || pathSegment.equals("..")),
        "Individual path segments must not be relative path modifiers (i.e. ., ..)");
    checkArgument(pathSegment.matches("[a-zA-Z0-9._-]+"),
        "Individual path segments must contain only letters, numbers, dots, dashes, and underscores"
            + " ([a-zA-Z0-9.-_])");
  }

  /**
   * Returns the SPIFFE ID from the leaf certificate, if present.
   *
   * @param certChain certificate chain to extract SPIFFE ID from
   */
  public static Optional<SpiffeId> extractSpiffeId(X509Certificate[] certChain)
      throws CertificateParsingException {
    checkArgument(checkNotNull(certChain, "certChain").length > 0, "certChain can't be empty");
    Collection<List<?>> subjectAltNames = certChain[0].getSubjectAlternativeNames();
    if (subjectAltNames == null) {
      return Optional.absent();
    }
    String uri = null;
    // Search for the unique URI SAN.
    for (List<?> altName : subjectAltNames) {
      if (altName.size() < 2 ) {
        continue;
      }
      if (URI_SAN_TYPE.equals(altName.get(0))) {
        if (uri != null) {
          throw new IllegalArgumentException("Multiple URI SAN values found in the leaf cert.");
        }
        uri = (String) altName.get(1);
      }
    }
    if (uri == null) {
      return Optional.absent();
    }
    return Optional.of(parse(uri));
  }

  /**
   * Loads a SPIFFE trust bundle from a file, parsing it from the JSON format.
   * In case of success, returns {@link SpiffeBundle}.
   * If any element of the JSON content is invalid or unsupported, an
   * {@link IllegalArgumentException} is thrown and the entire Bundle is considered invalid.
   *
   * @param trustBundleFile the file path to the JSON file containing the trust bundle
   * @see <a href="https://github.com/spiffe/spiffe/blob/main/standards/SPIFFE_Trust_Domain_and_Bundle.md">JSON format</a>
   * @see <a href="https://github.com/spiffe/spiffe/blob/main/standards/X509-SVID.md#61-publishing-spiffe-bundle-elements">JWK entry format</a>
   * @see <a href="https://datatracker.ietf.org/doc/html/rfc7517#appendix-B">x5c (certificate) parameter</a>
   */
  public static SpiffeBundle loadTrustBundleFromFile(String trustBundleFile) throws IOException {
    Map<String, ?> trustDomainsNode = readTrustDomainsFromFile(trustBundleFile);
    Map<String, List<X509Certificate>> trustBundleMap = new HashMap<>();
    Map<String, Long> sequenceNumbers = new HashMap<>();
    for (String trustDomainName : trustDomainsNode.keySet()) {
      Map<String, ?> domainNode = JsonUtil.getObject(trustDomainsNode, trustDomainName);
      if (domainNode.size() == 0) {
        trustBundleMap.put(trustDomainName, Collections.emptyList());
        continue;
      }
      Long sequenceNumber = JsonUtil.getNumberAsLong(domainNode, "spiffe_sequence");
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
    File file = new File(checkNotNull(filePath, "trustBundleFile"));
    String json = new String(Files.toByteArray(file), StandardCharsets.UTF_8);
    Object jsonObject = JsonParser.parse(json);
    if (!(jsonObject instanceof Map)) {
      throw new IllegalArgumentException(
          "SPIFFE Trust Bundle should be a JSON object. Found: "
              + (jsonObject == null ? null : jsonObject.getClass()));
    }
    @SuppressWarnings("unchecked")
    Map<String, ?> root = (Map<String, ?>)jsonObject;
    Map<String, ?> trustDomainsNode = JsonUtil.getObject(root, "trust_domains");
    checkNotNull(trustDomainsNode, "Mandatory trust_domains element is missing");
    checkArgument(trustDomainsNode.size() > 0, "Mandatory trust_domains element is missing");
    return trustDomainsNode;
  }

  private static void checkJwkEntry(Map<String, ?> jwkNode, String trustDomainName) {
    String kty = JsonUtil.getString(jwkNode, "kty");
    if (kty == null || !kty.equals(KTY_PARAMETER_VALUE)) {
      throw new IllegalArgumentException(String.format("'kty' parameter must be '%s' but '%s' "
              + "found. Certificate loading for trust domain '%s' failed.", KTY_PARAMETER_VALUE,
          kty, trustDomainName));
    }
    if (jwkNode.containsKey("kid")) {
      throw new IllegalArgumentException(String.format("'kid' parameter must not be set. "
              + "Certificate loading for trust domain '%s' failed.", trustDomainName));
    }
    String use = JsonUtil.getString(jwkNode, "use");
    if (use == null || !use.equals(USE_PARAMETER_VALUE)) {
      throw new IllegalArgumentException(String.format("'use' parameter must be '%s' but '%s' "
              + "found. Certificate loading for trust domain '%s' failed.", USE_PARAMETER_VALUE,
          use, trustDomainName));
    }
  }

  private static List<X509Certificate> extractCert(List<Map<String, ?>> keysNode,
      String trustDomainName) {
    List<X509Certificate> result = new ArrayList<>();
    for (Map<String, ?> keyNode : keysNode) {
      checkJwkEntry(keyNode, trustDomainName);
      List<String> rawCerts = JsonUtil.getListOfStrings(keyNode, "x5c");
      if (rawCerts == null) {
        break;
      }
      if (rawCerts.size() != 1) {
        throw new IllegalArgumentException(String.format("Exactly 1 certificate is expected, but "
            + "%s found. Certificate loading for trust domain '%s' failed.", rawCerts.size(),
            trustDomainName));
      }
      InputStream stream = new ByteArrayInputStream((CERTIFICATE_PREFIX + rawCerts.get(0) + "\n"
          + CERTIFICATE_SUFFIX)
          .getBytes(StandardCharsets.UTF_8));
      try {
        Collection<? extends Certificate> certs = CertificateFactory.getInstance("X509")
            .generateCertificates(stream);
        X509Certificate[] certsArray = certs.toArray(new X509Certificate[0]);
        assert certsArray.length == 1;
        result.add(certsArray[0]);
      } catch (CertificateException e) {
        throw new IllegalArgumentException(String.format("Certificate can't be parsed. Certificate "
            + "loading for trust domain '%s' failed.", trustDomainName), e);
      }
    }
    return result;
  }

  /**
   * Represents a SPIFFE ID as defined in the SPIFFE standard.
   * @see <a href="https://github.com/spiffe/spiffe/blob/master/standards/SPIFFE-ID.md">Standard</a>
   */
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
   * Represents a SPIFFE trust bundle; that is, a map from trust domain to set of trusted
   * certificates. Only trust domain's sequence numbers and x509 certificates are supported.
   * @see <a href="https://github.com/spiffe/spiffe/blob/main/standards/SPIFFE_Trust_Domain_and_Bundle.md#4-spiffe-bundle-format">Standard</a>
   */
  public static final class SpiffeBundle {

    private final ImmutableMap<String, Long> sequenceNumbers;

    private final ImmutableMap<String, ImmutableList<X509Certificate>> bundleMap;

    private SpiffeBundle(Map<String, Long> sequenceNumbers,
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
