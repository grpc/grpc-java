package flowsim;

public class Util {

  static final int DEFAULT_WINDOW = 64 * 1024;
  static final int DEFAULT_LATENCY = 10;
  static final int DEFAULT_BANDWIDTH = 10 * 1024 * 1024;
  static final int DEFAULT_DURATION = 1000;

  static final String PUBLISHED = "published";
  static final String AVAILABLE = "available";

  static final int HEADER = 1;
  static final int DATA = 2;

  static final int HEADER_SIZE = 5;
}
