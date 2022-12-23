/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.netty;

import io.grpc.ChannelCredentials;
import io.grpc.ExperimentalApi;

/** An insecure credential that upgrades from HTTP/1 to HTTP/2. */
@ExperimentalApi("There is no plan to make this API stable, given transport API instability")
public final class InsecureFromHttp1ChannelCredentials {
  private InsecureFromHttp1ChannelCredentials() {}

  /** Creates an insecure credential that will upgrade from HTTP/1 to HTTP/2. */
  public static ChannelCredentials create() {
    return NettyChannelCredentials.create(ProtocolNegotiators.plaintextUpgradeClientFactory());
  }
}
