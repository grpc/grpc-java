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

/**
 * Provider of credentials data which will be propagated to the server for each RPC. The actual call
 * credential to be used for a particular xDS communication will be chosen based on the bootstrap
 * configuration.
 */
@Internal
public abstract class XdsCallCredentialsProvider {
  /**
  * Creates a {@link CallCredentials} from the given jsonConfig, or
  * {@code null} if the given config is invalid. The provider is free to ignore
  * the config if it's not needed for producing the call credentials.
  *
  * @param jsonConfig json config that can be consumed by the provider to create
  *                   the call credentials
  *
  */
  protected abstract CallCredentials newCallCredentials(Map<String, ?> jsonConfig);

  /**
   * Returns the xDS call credential name associated with this provider which makes it selectable
   * via {@link XdsCallCredentialsRegistry#getProvider}. This is called only when the class is
   * loaded. It shouldn't change, and there is no point doing so.
   */
  protected abstract String getName();
}
