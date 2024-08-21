package io.grpc.util;

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
    return new SpiffeIdInfoImpl(trustDomain, path);
  }

  private static void validateFormat(String uri) throws IllegalArgumentException {
    if (uri == null || uri.length()==0) {
      throw new IllegalArgumentException("Spiffe Id can't be empty");
    }
    if (!uri.startsWith(PREFIX)) {
      throw new IllegalArgumentException("Spiffe Id must start with " + PREFIX);
    }
    if (uri.contains("#")) {
      throw new IllegalArgumentException("Spiffe Id must not contain query fragments");
    }
    if (uri.contains("?")) {
      throw new IllegalArgumentException("Spiffe Id must not contain query parameters");
    }
  }

  private static void validateTrustDomain(String trustDomain) throws IllegalArgumentException {
    if (trustDomain.length() == 0) {
      throw new IllegalArgumentException("Trust Domain can't be empty");
    }
    if (!trustDomain.matches("[a-z0-9._-]+")) {
      throw new IllegalArgumentException("Trust Domain contain only letters, numbers, dots, dashes, and underscores ([a-z0-9.-_])");
    }
  }

  private static void validatePath(String path) {
    if (path.length() == 0) {
      throw new IllegalArgumentException("Path can't be empty");
    }
    String pathWithoutPrefix = path.substring(1);
    for (String segment : pathWithoutPrefix.split("/")) {
      validatePathSegmentCharacters(segment);
    }
  }

  private static void validatePathSegmentCharacters(String pathSegment) {
    boolean valid =
      pathSegment
        .chars()
        .allMatch(
            c -> {
              char ch = (char) c;
              return (ch >= '0' && ch <= '9')
                  || (ch >= 'a' && ch <= 'z')
                  || (ch >= 'A' && ch <= 'Z');
            });
    if (!valid) {
      throw new IllegalArgumentException("Path contains illegal characters");
    }
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
