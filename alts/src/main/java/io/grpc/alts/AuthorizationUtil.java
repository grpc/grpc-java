/*
 * Copyright 2019 The gRPC Authors
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

import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.alts.internal.AltsAuthContext;
import io.grpc.alts.internal.AltsProtocolNegotiator;
import java.util.Collection;

/** Utility class for ALTS client authorization. */
public final class AuthorizationUtil {

  private AuthorizationUtil() {}

  /**
   * Given a server call, performs client authorization check, i.e., checks if the client service
   * account matches one of the expected service accounts. It returns OK if client is authorized and
   * an error otherwise.
   */
  public static Status clientAuthorizationCheck(
      ServerCall<?, ?> call, Collection<String> expectedServiceAccounts) {
    AltsAuthContext altsContext =
        (AltsAuthContext) call.getAttributes().get(AltsProtocolNegotiator.AUTH_CONTEXT_KEY);
    if (altsContext == null) {
      return Status.PERMISSION_DENIED.withDescription("Peer ALTS AuthContext not found");
    }
    if (expectedServiceAccounts.contains(altsContext.getPeerServiceAccount())) {
      return Status.OK;
    }
    return Status.PERMISSION_DENIED.withDescription(
        "Client " + altsContext.getPeerServiceAccount() + " is not authorized");
  }
}
