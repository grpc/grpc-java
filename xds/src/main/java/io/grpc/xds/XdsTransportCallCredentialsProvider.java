package io.grpc.xds;

import io.grpc.CallCredentials;
import io.grpc.Internal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores custom call credentials to use on the xDS transport for specific targets, and supplies
 * them to {@link GrpcXdsTransport}.
 */
@Internal
public final class XdsTransportCallCredentialsProvider {
  private static final Map<String, CallCredentials> xdsTransportCallCredentials =
      new ConcurrentHashMap<>();

  public static CallCredentials getTransportCallCredentials(String target) {
    return xdsTransportCallCredentials.get(target);
  }

  /**
   * Registers a custom {@link CallCredentials} that is to be used on the xDS transport when
   * resolving the given target. Must be called before the xDS client for the target is created
   * (i.e. before the application creates a channel to the target).
   *
   * <p>A custom {@code CallCredentials} can only be set once on the xDS transport; in other words,
   * it is not possible to change the custom transport {@code CallCredentials} for an existing xDS
   * client. If {@code setTransportCallCredentials} is called when there is already an existing xDS
   * client for the target, then this does nothing and returns false.
   */
  public static boolean setTransportCallCredentials(String target, CallCredentials creds) {
    if (SharedXdsClientPoolProvider.getDefaultProvider().get(target) != null) {
      return false;
    }
    xdsTransportCallCredentials.put(target, creds);
    return true;
  }

  private XdsTransportCallCredentialsProvider() {}
}
