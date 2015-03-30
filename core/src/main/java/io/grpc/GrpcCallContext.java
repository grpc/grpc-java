package io.grpc;

/**
 * Holds state pertaining to a single GRPC call.
 */
public class GrpcCallContext {

  private final GrpcSession session;
  private final String methodName;
  private final Metadata.Headers headers;

  /**
   * Constructor.
   *
   * @param methodName name of method being called
   * @param headers    headers for current call
   * @param session    session associated with the current call
   */
  public GrpcCallContext(String methodName, Metadata.Headers headers, GrpcSession session) {
    this.methodName = methodName;
    this.headers = headers;
    this.session = session;
  }

  /**
   * Get the methodName for the current call.
   *
   * @return method name for the current call
   */
  public String getMethodName() {
    return methodName;
  }

  /**
   * Get the headers for the current call.
   *
   * @return headers for the current call
   */
  public Metadata.Headers getHeaders() {
    return headers;
  }

  /**
   * Gets the current GrpcSession, which lasts across multiple method calls on a single
   * transport-defined session (likely a TCP connection).
   *
   * @return current session
   */
  public GrpcSession getSession() {
    return session;
  }

  static final ThreadLocal<GrpcCallContext> THREAD_LOCAL = new ThreadLocal<GrpcCallContext>();

  /**
   * Gets the active GrpcCallContext (from the ThreadLocal).
   *
   * @return active GrpcCallContext
   */
  public static GrpcCallContext get() {
    GrpcCallContext session = THREAD_LOCAL.get();
    assert session != null;

    return session;
  }

  /**
   * Sets the active GrpcCallContext (sets the ThreadLocal). Should only be called when no
   * GrpcCallContext is active.
   *
   * @param session GrpcCallContext to set as active
   */
  static void enter(GrpcCallContext session) {
    assert THREAD_LOCAL.get() == null;
    THREAD_LOCAL.set(session);
  }

  /**
   * Gets the active GrpcCallContext (clears the ThreadLocal). Should only be called when a
   * GrpcCallContext is active.
   */
  static void exit() {
    assert THREAD_LOCAL.get() != null;
    THREAD_LOCAL.set(null);
  }

}
