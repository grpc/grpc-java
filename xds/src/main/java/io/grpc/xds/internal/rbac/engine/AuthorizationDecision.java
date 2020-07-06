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

package io.grpc.xds.internal;

import java.lang.StringBuilder;

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

  private Decision decision;
  private String authorizationContext;

  /**
   * Creates a new authorization decision using the input {@code decision} 
   * for resolving authorization decision
   * and {@code authorizationContext} for resolving authorization context.
   */
  public AuthorizationDecision(Decision decision, String authorizationContext) {
    this.decision = decision;
    this.authorizationContext = authorizationContext;
  }

  /** Returns the authorization decision. */
  public Decision getDecision() {
    return this.decision;
  }

  /** Returns the authorization context. */
  public String getAuthorizationContext() {
    return this.authorizationContext;
  }

  @Override
  public String toString() {
    StringBuilder authDecision = new StringBuilder();
    switch (this.decision) {
      case ALLOW: 
        authDecision.append("Authorization Decision: ALLOW. \n");
        break;
      case DENY: 
        authDecision.append("Authorization Decision: DENY. \n");
        break;
      case UNKNOWN: 
        authDecision.append("Authorization Decision: UNKNOWN. \n");
        break;
      default: 
        authDecision.append("");
        break;
    }
    authDecision.append(this.authorizationContext);
    return authDecision.toString();
  }
}
