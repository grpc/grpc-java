package io.grpc.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class SpiffeIdParserImpl implements SpiffeIdParser {

  private final static String PREFIX = "spiffe://";

  @Override
  public SpiffeIdInfo parse(String uri) {
    validateFormat(uri);
    String domainAndPath = uri.substring(PREFIX.length());
    String trustDomain;
    String path = "";
    if (!domainAndPath.contains("/")) {
      trustDomain = domainAndPath;
    } else {
      String[] parts = domainAndPath.split("/", 2);
      trustDomain = parts[0];
      path = parts[1];
    }
    validateTrustDomain(trustDomain);
    validatePath(path);
    if (!path.isEmpty()) {
      path = "/" + path;
    }
    return new SpiffeIdInfoImpl(trustDomain, path);
  }

  private static void validateFormat(String uri) throws IllegalArgumentException {
    checkArgument(checkNotNull(uri, "uri").length() > 0, "Spiffe Id can't be empty");
    checkArgument(uri.toLowerCase().startsWith(PREFIX), "Spiffe Id must start with " + PREFIX);
    checkArgument(!uri.contains("#"), "Spiffe Id must not contain query fragments");
    checkArgument(!uri.contains("?"), "Spiffe Id must not contain query parameters");
  }

  private static void validateTrustDomain(String trustDomain) throws IllegalArgumentException {
    checkArgument(!trustDomain.isEmpty(), "Trust Domain can't be empty");
    checkArgument(trustDomain.length() < 256, "Trust Domain maximum length is 255 characters");
    checkArgument(trustDomain.matches("[a-z0-9._-]+"),
        "Trust Domain must contain only letters, numbers, dots, dashes, and underscores ([a-z0-9.-_])");
  }

  private static void validatePath(String path) {
    if (path.isEmpty()) {
      return;
    }
    checkArgument(!path.endsWith("/"), "Path must not include a trailing '/'");
    for (String segment : path.split("/")) {
      validatePathSegment(segment);
    }
  }

  private static void validatePathSegment(String pathSegment) {
    checkArgument(!pathSegment.isEmpty(), "Individual path segments must not be empty");
    checkArgument(!(pathSegment.equals(".") || pathSegment.equals("..")),
        "Individual path segments must not be relative path modifiers (i.e. ., ..)");
    checkArgument(pathSegment.matches("[a-zA-Z0-9._-]+"),
        "Individual path segments must contain only letters, numbers, dots, dashes, and underscores ([a-zA-Z0-9.-_])");
  }

  private static class SpiffeIdInfoImpl implements SpiffeIdInfo {

    private final String trustDomain;
    private final String path;

    private SpiffeIdInfoImpl(String trustDomain, String path) {
      this.trustDomain = trustDomain;
      this.path = path;
    }


    @Override
    public String getTrustDomain() {
      return trustDomain;
    }

    @Override
    public String getPath() {
      return path;
    }
  }

}
