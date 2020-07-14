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

package io.grpc.xds.internal.engine;

import java.lang.StringBuilder;
import java.util.List;

/** 
 * The AuthorizationDecision class holds authorization decision 
 * returned by Cel Evaluation Engine. 
 */
public class AuthorizationDecision {
  /** The Decision enum represents the possible decisions outputted by Cel Evaluation Engine.*/
  public enum Decision {
    /** 
     * The Decision ALLOW indicates that Cel Evaluate Engine 
     * had authorized the gRPC call and allowed the gRPC call to go through.
     */
    ALLOW,
    /** 
     * The Decision DENY indicates that Cel Evaluate Engine 
     * had authorized the gRPC call and denied the gRPC call from going through.
     */
    DENY,
    /** 
     * The Decision UNKNOWN indicates that Cel Evaluate Engine 
     * did not have enough information to authorize the gRPC call. 
     * */
    UNKNOWN,
  }

  private final Decision decision;
  private final List<String> matchingPolicyNames;

  /**
   * Creates a new authorization decision using the input {@code decision} 
   * for resolving authorization decision
   * and {@code matchingPolicyNames} for resolving authorization context.
   */
  public AuthorizationDecision(Decision decision, List<String> matchingPolicyNames) {
    this.decision = decision;
    this.matchingPolicyNames = matchingPolicyNames;
  }

  /** Returns the authorization decision. */
  public Decision getDecision() {
    return this.decision;
  }

  /** Returns the matching policy list. */
  public List<String> getMatchingPolicyNames() {
    return this.matchingPolicyNames;
  }

  @Override
  public String toString() {
    StringBuilder authContext = new StringBuilder();
    switch (this.decision) {
      case ALLOW: 
        authContext.append("Authorization Decision: ALLOW. \n");
        break;
      case DENY: 
        authContext.append("Authorization Decision: DENY. \n");
        break;
      case UNKNOWN: 
        authContext.append("Authorization Decision: UNKNOWN. \n");
        break;
      default: 
        authContext.append("");
        break;
    }
    for (String matchingPolicyName : this.matchingPolicyNames) {
      authContext.append(matchingPolicyName + "; \n");
    }
    return authContext.toString();
  }
}
