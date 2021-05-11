/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.xds.internal.rbac.engine;

import com.google.auto.value.AutoValue;
import io.grpc.xds.EvaluateArgs;
import javax.annotation.Nullable;

/** An authorization engine evaluates input arguments against internal configuration policies and
 * returns an authorization decision. */
public interface AuthorizationEngineInterface {

  /** An authorization decision provides information about the decision type and the policy name
   * identifier based on the authorization engine evaluation. */
  @AutoValue
  abstract class AuthDecision {
    public enum DecisionType {
      ALLOW,
      DENY,
    }

    abstract DecisionType decision();

    @Nullable
    abstract String matchingPolicyName();

    public static AuthDecision create(DecisionType decisionType, @Nullable String matchingPolicy) {
      return new AutoValue_AuthorizationEngineInterface_AuthDecision(decisionType,
          matchingPolicy);
    }
  }

  /** Returns authorization decision for the request arguments. */
  AuthDecision evaluate(EvaluateArgs args);
}
