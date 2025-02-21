/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds;

import io.grpc.CallCredentials;
import io.grpc.Internal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores custom call credentials to use on the xDS transport for specific targets, and supplies
 * them to the xDS client.
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
