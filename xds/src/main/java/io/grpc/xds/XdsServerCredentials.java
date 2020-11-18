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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.ExperimentalApi;
import io.grpc.ServerCredentials;
import io.grpc.netty.InternalNettyServerCredentials;
import io.grpc.netty.InternalProtocolNegotiator;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators;

@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7621")
public class XdsServerCredentials {
  private XdsServerCredentials() {} // prevent instantiation

  /**
   * Creates credentials to be configured by xDS, falling back to other credentials if no
   * TLS configuration is provided by xDS.
   *
   * @param fallback Credentials to fall back to.
   *
   * @throws IllegalArgumentException if fallback is unable to be used
   */
  public static ServerCredentials create(ServerCredentials fallback) {
    InternalProtocolNegotiator.ServerFactory fallbackNegotiator =
        InternalNettyServerCredentials.toNegotiator(checkNotNull(fallback, "fallback"));
    return InternalNettyServerCredentials.create(
        SdsProtocolNegotiators.serverProtocolNegotiatorFactory(fallbackNegotiator));
  }
}
