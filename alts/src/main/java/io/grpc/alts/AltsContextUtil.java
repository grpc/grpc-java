/*
 * Copyright 2018 The gRPC Authors
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


package io.grpc.alts;

import io.grpc.ExperimentalApi;
import io.grpc.ServerCall;
import io.grpc.alts.internal.AltsInternalContext;
import io.grpc.alts.internal.AltsProtocolNegotiator;

/** Utility class for {@link AltsContext}. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7864")
public final class AltsContextUtil {

  private AltsContextUtil() {}

  /**
   * Creates a {@link AltsContext} from ALTS context information in the {@link ServerCall}.
   *
   * @param call the {@link ServerCall} containing the ALTS information
   * @return the created {@link AltsContext}
   * @throws IllegalArgumentException if the {@link ServerCall} has no ALTS information.
   */
  public static AltsContext createFrom(ServerCall<?,?> call) {
    Object authContext = call.getAttributes().get(AltsProtocolNegotiator.AUTH_CONTEXT_KEY);
    if (!(authContext instanceof AltsInternalContext)) {
      throw new IllegalArgumentException("No ALTS context information found");
    }
    return new AltsContext((AltsInternalContext) authContext);
  }

  /**
   * Checks if the {@link ServerCall} contains ALTS information.
   *
   * @param call the {@link ServerCall} to check
   * @return true, if the {@link ServerCall} contains ALTS information and false otherwise.
   */
  public static boolean check(ServerCall<?,?> call) {
    Object authContext = call.getAttributes().get(AltsProtocolNegotiator.AUTH_CONTEXT_KEY);
    return authContext instanceof AltsInternalContext;
  }
}
