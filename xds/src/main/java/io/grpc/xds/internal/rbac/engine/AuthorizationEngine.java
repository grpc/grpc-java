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

package io.grpc.xds.internal.rbac.engine;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.expr.v1alpha1.Expr;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Descriptors.Descriptor;
import io.envoyproxy.envoy.config.rbac.v2.Policy;
import io.envoyproxy.envoy.config.rbac.v2.RBAC;
import io.envoyproxy.envoy.config.rbac.v2.RBAC.Action;
import io.grpc.xds.internal.rbac.engine.cel.Activation;
import io.grpc.xds.internal.rbac.engine.cel.DefaultDispatcher;
import io.grpc.xds.internal.rbac.engine.cel.DefaultInterpreter;
import io.grpc.xds.internal.rbac.engine.cel.DescriptorMessageProvider;
import io.grpc.xds.internal.rbac.engine.cel.Dispatcher;
import io.grpc.xds.internal.rbac.engine.cel.IncompleteData;
import io.grpc.xds.internal.rbac.engine.cel.Interpretable;
import io.grpc.xds.internal.rbac.engine.cel.Interpreter;
import io.grpc.xds.internal.rbac.engine.cel.InterpreterException;
import io.grpc.xds.internal.rbac.engine.cel.RuntimeTypeProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CEL-based Authorization Engine is part of the authorization framework in gRPC.
 * CEL-based Authorization Engine takes one or two Envoy RBAC policies as input
 * and uses CEL library to evaluate the condition field 
 * inside each RBAC policy based on the provided Envoy Attributes. 
 * CEL-based Authorization Engine will generate an authorization decision which
 * could be ALLOW, DENY or UNKNOWN.
 * 
 * <p>Use as in:
 * 
 * <pre>
 *  AuthorizationEngine engine = new AuthorizationEngine(rbacPolicy);
 *  AuthorizationDecision result = engine.evaluate(new EvaluateArgs(call, headers));
 * </pre>
 */
public class AuthorizationEngine {
  /**
   * RbacEngine is an inner class that holds RBAC action 
   * and a list of conditions in RBAC policy.
   */
  private static class RbacEngine {
    @SuppressWarnings("UnusedVariable")
    private final Action action;
    private final ImmutableMap<String, Expr> conditions;

    public RbacEngine(Action action, ImmutableMap<String, Expr> conditions) {
      this.action = checkNotNull(action);
      this.conditions = checkNotNull(conditions);
    }
  }

  private static final Logger log = Logger.getLogger(AuthorizationEngine.class.getName());
  private final RbacEngine allowEngine;
  private final RbacEngine denyEngine;

  /**
   * Creates a CEL-based Authorization Engine from one Envoy RBAC policy.
   * @param rbacPolicy input Envoy RBAC policy.
   */
  public AuthorizationEngine(RBAC rbacPolicy) {
    Map<String, Expr> conditions = new LinkedHashMap<>();
    for (Map.Entry<String, Policy> policy: rbacPolicy.getPoliciesMap().entrySet()) {
      conditions.put(policy.getKey(), policy.getValue().getCondition());
    }
    allowEngine = (rbacPolicy.getAction() == Action.ALLOW) 
        ? new RbacEngine(Action.ALLOW, ImmutableMap.copyOf(conditions)) : null; 
    denyEngine = (rbacPolicy.getAction() == Action.DENY) 
        ? new RbacEngine(Action.DENY, ImmutableMap.copyOf(conditions)) : null; 
  }

  /**
   * Creates a CEL-based Authorization Engine from two Envoy RBAC policies.
   * When it takes two RBAC policies, 
   * the order has to be a DENY policy followed by an ALLOW policy.
   * @param denyPolicy input Envoy RBAC policy with DENY action.
   * @param allowPolicy input Envoy RBAC policy with ALLOW action.
   * @throws IllegalArgumentException if the user inputs an invalid RBAC list.
   */
  public AuthorizationEngine(RBAC denyPolicy, RBAC allowPolicy) {
    checkArgument(
        denyPolicy.getAction() == Action.DENY && allowPolicy.getAction() == Action.ALLOW,
        "Invalid RBAC list, " 
        + "must provide a RBAC with DENY action followed by a RBAC with ALLOW action. ");
    Map<String, Expr> denyConditions = new LinkedHashMap<>();
    for (Map.Entry<String, Policy> policy: denyPolicy.getPoliciesMap().entrySet()) {
      denyConditions.put(policy.getKey(), policy.getValue().getCondition());
    }
    denyEngine = new RbacEngine(Action.DENY, ImmutableMap.copyOf(denyConditions));
    Map<String, Expr> allowConditions = new LinkedHashMap<>();
    for (Map.Entry<String, Policy> policy: allowPolicy.getPoliciesMap().entrySet()) {
      allowConditions.put(policy.getKey(), policy.getValue().getCondition());
    }
    allowEngine = new RbacEngine(Action.ALLOW, ImmutableMap.copyOf(allowConditions));   
  }

  /**
   * The evaluate function performs the core authorization mechanism 
   * of CEL-based Authorization Engine.
   * It determines whether a gRPC call is allowed, denied, or unable to be decided.
   * @param args evaluate argument that is used to evaluate the RBAC conditions.
   * @return an AuthorizationDecision generated by CEL-based Authorization Engine.
   */
  public AuthorizationDecision evaluate(EvaluateArgs args) {
    List<String> unknownPolicyNames = new ArrayList<>();
    // Set up activation used in CEL library's eval function.
    Activation activation = Activation.copyOf(args.generateEnvoyAttributes());
    // Iterate through denyEngine's map.
    // If there is match, immediately return DENY. 
    // If there are unknown conditions, return UNKNOWN. 
    // If all non-match, then iterate through allowEngine.
    if (denyEngine != null) {
      AuthorizationDecision authzDecision = evaluateEngine(denyEngine.conditions.entrySet(), 
          AuthorizationDecision.Output.DENY, unknownPolicyNames, activation);
      if (authzDecision != null) {
        return authzDecision;
      }
      if (!unknownPolicyNames.isEmpty()) {
        return new AuthorizationDecision(
            AuthorizationDecision.Output.UNKNOWN, unknownPolicyNames);
      }
    }
    // Once we enter allowEngine, if there is a match, immediately return ALLOW. 
    // In the end of iteration, if there are unknown conditions, return UNKNOWN.
    // If all non-match, return DENY.
    if (allowEngine != null) {
      AuthorizationDecision authzDecision = evaluateEngine(allowEngine.conditions.entrySet(), 
          AuthorizationDecision.Output.ALLOW, unknownPolicyNames, activation);
      if (authzDecision != null) {
        return authzDecision;
      }
      if (!unknownPolicyNames.isEmpty()) {
        return new AuthorizationDecision(
            AuthorizationDecision.Output.UNKNOWN, unknownPolicyNames);
      }
    }
    // Return ALLOW if it only has a denyEngine and it’s unmatched.
    if (this.allowEngine == null && this.denyEngine != null) {
      return new AuthorizationDecision(
          AuthorizationDecision.Output.ALLOW, new ArrayList<String>());
    }
    // Return DENY if none of denyEngine and allowEngine matched, 
    // or the single allowEngine is unmatched when there is only one allowEngine.
    return new AuthorizationDecision(AuthorizationDecision.Output.DENY, new ArrayList<String>());
  }

  /** Evaluate a single RbacEngine. */
  protected AuthorizationDecision evaluateEngine(Set<Map.Entry<String, Expr>> entrySet, 
      AuthorizationDecision.Output decision, List<String> unknownPolicyNames, 
      Activation activation) {
    for (Map.Entry<String, Expr> condition : entrySet) {
      try {
        if (matches(condition.getValue(), activation)) {
          return new AuthorizationDecision(decision, 
              new ArrayList<String>(Arrays.asList(new String[] {condition.getKey()})));
        }
      } catch (InterpreterException e) {
        unknownPolicyNames.add(condition.getKey());
      }
    }
    return null;
  }

  /** Evaluate if a condition matches the given Enovy Attributes using CEL library. */
  protected boolean matches(Expr condition, Activation activation) throws InterpreterException {
    // Set up interpretable used in CEL library's eval function.
    List<Descriptor> descriptors = new ArrayList<>();
    RuntimeTypeProvider messageProvider = DescriptorMessageProvider.dynamicMessages(descriptors);
    Dispatcher dispatcher = DefaultDispatcher.create();
    Interpreter interpreter = new DefaultInterpreter(messageProvider, dispatcher);
    Interpretable interpretable = interpreter.createInterpretable(condition);
    // Parse the generated result object to a boolean variable.
    try {
      Object result = interpretable.eval(activation);
      if (result instanceof Boolean) {
        return Boolean.parseBoolean(result.toString());
      }
      // Throw an InterpreterException if there are missing Envoy Attributes.
      if (result instanceof IncompleteData) {
        throw new InterpreterException.Builder("Envoy Attributes gotten are incomplete.").build(); 
      }
    } catch (InterpreterException e) {
      // If any InterpreterExceptions are catched, throw it and log the error.
      log.log(Level.WARNING, e.toString(), e);
      throw e;
    }
    return false;
  }
}
