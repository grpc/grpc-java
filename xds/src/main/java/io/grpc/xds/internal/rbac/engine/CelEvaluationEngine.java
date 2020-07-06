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

import com.google.api.expr.v1alpha1.CheckedExpr;
import com.google.api.expr.v1alpha1.Expr;
import com.google.api.expr.v1alpha1.ParsedExpr;
import com.google.api.expr.v1alpha1.SourceInfo;
import com.google.api.expr.v1alpha1.Type;
import com.google.api.expr.v1alpha1.Type.MapType;
import com.google.api.expr.v1alpha1.Type.PrimitiveType;
import com.google.api.expr.v1alpha1.Type.WellKnownType;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Descriptors.Descriptor;
import io.envoyproxy.envoy.config.rbac.v2.Policy;
import io.envoyproxy.envoy.config.rbac.v2.RBAC;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.xds.InterpreterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cel Evaluation Engine is part of the authorization framework in gRPC.
 * Cel Evaluation Engine takes one or two Envoy RBAC policies as input
 * and uses CEL library to evaluate the condition field 
 * inside each RBAC policy based on the provided Envoy Attributes. 
 * Cel Evaluation Engine will generate an authorization decision which
 * could be ALLOW, DENY or UNKNOWN.
 * 
 * <p>Use as in:
 * 
 * <pre>
 *  CelEvaluationEngine engine = new CelEvaluationEngine(rbacPolicies);
 *  AuthorizationDecision result = engine.evaluate();
 * </pre>
 */
public class CelEvaluationEngine<ReqT, RespT> {
  private final List<RBAC.Action> action;
  private final List<ImmutableMap<String, CheckedExpr>> conditions;

  /**
   * Creates a Cel Evaluation Engine from a list of Envoy RBACs.
   * The constructor can take either one or two Envoy RBACs.
   * When it takes two RBACs, the order has to be a RBAC with DENY action
   * followed by a RBAC with ALLOW action.
   * @param rbacs input Envoy RBAC list.
   * @throws IllegalArgumentException if the user inputs an invalid RBAC list.
   */
  public CelEvaluationEngine(List<RBAC> rbacs) throws IllegalArgumentException {
    if (rbacs.size() < 1 || rbacs.size() > 2) {
      throw new IllegalArgumentException(
        "Invalid RBAC list size, must provide either one RBAC or two RBACs. ");
    } 
    if (rbacs.size() == 2 && (rbacs.get(0).getAction() != RBAC.Action.DENY 
        || rbacs.get(1).getAction() != RBAC.Action.ALLOW)) {
      throw new IllegalArgumentException( "Invalid RBAC list, " 
          + "must provide a RBAC with DENY action followed by a RBAC with ALLOW action. ");
    }
    this.action = new ArrayList<>();
    this.conditions = new ArrayList<>();
    for (int i = 0; i < rbacs.size(); i++) {
      RBAC rbac = rbacs.get(i);
      Map<String, Expr> conditions = new HashMap<>();
      for (Map.Entry<String, Policy> entry: rbac.getPolicies().entrySet()) {
        conditions.put(entry.getKey(), entry.getValue().getCondition());
      }
      this.action.add(Preconditions.checkNotNull(rbac.getAction()));
      this.conditions.add(
          Preconditions.checkNotNull(ImmutableMap.copyOf(parseConditions(conditions))));
    }
  }

  /** Convert RBAC conditions from type Expr to type CheckedExpr. */
  private Map<String, CheckedExpr> parseConditions(Map<String, Expr> conditions) {
    Map<String, CheckedExpr> parsedConditions = new HashMap<>();
    Env env = envSetup();
    for (Map.Entry<String, Expr> entry : conditions.entrySet()) {
      Expr condition = entry.getValue();
      // Convert Expr to ParsedExpr by adding an empty SourceInfo field.
      ParsedExpr parsedCondition = ParsedExpr.newBuilder()
          .setExpr(condition)
          .setSourceInfo(SourceInfo.newBuilder().build())
          .build();
      // Convert ParsedExpr to CheckedExpr.
      CheckedExpr checkedCondition = ExprChecker.check(env, "", parsedCondition);
      parsedConditions.put(entry.getKey(), checkedCondition);
    }
    return parsedConditions;
  }

  /** Set up environment used for ExprChecker conversion. */
  private Env envSetup() {
    // Define relevent Type variables for environment setup.
    final Type stringType = Type.newBuilder()
        .setPrimitive(PrimitiveType.STRING)
        .build();
    final Type int64Type = Type.newBuilder()
        .setPrimitive(PrimitiveType.INT64)
        .build();
    final Type unknownType = Type.newBuilder()
        .setWellKnown(WellKnownType.WELL_KNOWN_TYPE_UNSPECIFIED)
        .build();
    final Type stringMapType = Type.newBuilder()
        .setMapType(
            MapType.newBuilder()
                .setKeyType(stringType)
                .setValueType(unknownType))
        .build();
    // Create the environment variable.
    Errors errors = new Errors("source_location", null);
    TypeProvider typeProvider = new DescriptorTypeProvider();
    Env env = Env.standard(errors, typeProvider);
    // Add Envoy Attributes and their corresponding Type values into env.
    env.add("requestUrlPath", stringType);
    env.add("requestHost", stringType);
    env.add("requestMethod", stringType);
    env.add("requestHeaders", stringMapType);
    env.add("sourceAddress", stringType);
    env.add("sourcePort", int64Type);
    env.add("destinationAddress", stringType);
    env.add("destinationPort", int64Type);
    env.add("connectionRequestedServerName", stringType);
    env.add("onnectionUriSanPeerCertificate", stringType);
    if (errors.getErrorCount() > 0) {
      throw new RuntimeException(errors.getAllErrorsAsString());
    }
    return env;
  }

  /**
   * The evaluate function performs the core authorization mechanism of Cel Evaluation Engine.
   * Determines whether a gRPC call is allowed, denied, or unable to decide.
   * @param args evaluate argument that is used to evaluate the RBAC conditions.
   * @return an AuthorizationDecision generated by Cel Evaluation Engine.
   * @throws InterpreterException if something goes wrong in CEL library.
   */
  public AuthorizationDecision evaluate(EvaluateArgs<ReqT, RespT> args) 
    throws InterpreterException {
    AuthorizationDecision.Decision authorizationDecision = null;
    String authorizationContext = "";
    // Go through each RBAC in the Envoy RBAC list.
    for (int i = 0; i < this.action.size(); i++) {
      // Go through each condition in the RBAC policy.
      for (Map.Entry<String, CheckedExpr> entry : this.conditions.get(i).entrySet()) {
        try {
          if (matches(entry.getValue(), args)) {
            if (this.action.get(i) == RBAC.Action.ALLOW) {
              authorizationDecision = AuthorizationDecision.Decision.ALLOW;
            } else {
              authorizationDecision = AuthorizationDecision.Decision.DENY;
            }
            authorizationContext = "Policy matched: " + entry.getKey();
            return new AuthorizationDecision(authorizationDecision, authorizationContext);
          }
        } catch (IllegalArgumentException e) {
          // The authorization decision is UNKNOWN if any Envoy Attributes are missing in args.
          authorizationDecision = AuthorizationDecision.Decision.UNKNOWN;
          authorizationContext = "Unable to determine. Error: " + e.toString();
          return new AuthorizationDecision(authorizationDecision, authorizationContext);
        }
      }
    }
    if (this.action.size() == 2) {
      // If there are 2 RBACs, the authorization decision is DENY by default.
      authorizationDecision = AuthorizationDecision.Decision.DENY;
    } else if (this.action.get(0) == RBAC.Action.ALLOW) {
      authorizationDecision = AuthorizationDecision.Decision.DENY;
    } else {
      authorizationDecision = AuthorizationDecision.Decision.ALLOW;
    }
    authorizationContext = "No policies matched. ";
    return new AuthorizationDecision(authorizationDecision, authorizationContext);
  }

  /** Evaluate if a condition matches the given Enovy Attributes using Cel library. */
  private boolean matches(CheckedExpr conditions, EvaluateArgs<ReqT, RespT> args) 
    throws InterpreterException, IllegalArgumentException {
    // Set up interpreter used in Cel library's eval function.
    List<Descriptor> descriptors = new ArrayList<>();
    RuntimeTypeProvider messageProvider = DescriptorMessageProvider.dynamicMessages(descriptors);
    Dispatcher dispatcher = DefaultDispatcher.create();
    Interpreter interpreter = new DefaultInterpreter(messageProvider, dispatcher);
    // Set up activation used in Cel library's eval function.
    ImmutableMap<String, Object> apiAttributes = extractFields(args);
    Activation activation = Activation.copyOf(apiAttributes);
    // Parse the generated result object to a boolean variable.
    Object result = interpreter.createInterpretable(conditions).eval(activation);
    if (result instanceof Boolean) {
      return Boolean.parseBoolean(result.toString());
    }
    return false;
  }

  /** Extract Envoy Attributes from EvaluateArgs. */
  private ImmutableMap<String, Object> extractFields(
      EvaluateArgs<ReqT, RespT> args) throws IllegalArgumentException {
    if (args == null) {
      throw new IllegalArgumentException("EvaluateArgs is not found. ");
    }
    // Add the extracted Envoy Attributes to the attributes map.
    Map<String, Object> attributes = new HashMap<>();
    setRequestHost(args, attributes);
    setRequestMethod(args, attributes);
    setRequestHeaders(args, attributes);
    setSourceAddress(args, attributes);
    setDestinationAddress(args, attributes);
    setConnectionRequestedServerName(args, attributes);
    return ImmutableMap.copyOf(attributes);
  }

  // TBD
  // private void setRequestUrlPath(
  //    EvaluateArgs<ReqT, RespT> args, Map<String, Object> attributes) {}

  /** Extract and set the RequestHost field. */
  private void setRequestHost(
      EvaluateArgs<ReqT, RespT> args, Map<String, Object> attributes) 
      throws IllegalArgumentException {
    String requestHost = args.getCall().getMethodDescriptor().getServiceName();
    if (requestHost == null || requestHost.length() == 0) {
      throw new IllegalArgumentException("RequestHost field is not found. ");
    }
    attributes.put("requestHost", requestHost);
  }

  /** Extract and set the RequestMethod field. */
  private void setRequestMethod(
      EvaluateArgs<ReqT, RespT> args, Map<String, Object> attributes) 
      throws IllegalArgumentException {
    String requestMethod = args.getCall().getMethodDescriptor().getFullMethodName();
    if (requestMethod == null || requestMethod.length() == 0) {
      throw new IllegalArgumentException("RequestMethod field is not found. ");
    }
    attributes.put("requestMethod", requestMethod);
  }

  /** Extract and set the RequestHeaders field. */
  private void setRequestHeaders(
      EvaluateArgs<ReqT, RespT> args, Map<String, Object> attributes) 
      throws IllegalArgumentException {
    Metadata requestHeaders = args.getHeaders();
    if (requestHeaders == null) {
      throw new IllegalArgumentException("RequestHeaders field is not found. ");
    }
    attributes.put("requestHeaders", requestHeaders);
  }

  /** Extract and set the SourceAddress field. */
  private void setSourceAddress(
      EvaluateArgs<ReqT, RespT> args, Map<String, Object> attributes) 
      throws IllegalArgumentException {
    String sourceAddress = args.getCall().getAttributes()
        .get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR).toString();
    if (sourceAddress == null || sourceAddress.length() == 0) {
      throw new IllegalArgumentException("SourceAddress field is not found. ");
    }
    attributes.put("sourceAddress", sourceAddress);
  }

  // TBD
  // private void setSourcePort(
  //    EvaluateArgs<ReqT, RespT> args, Map<String, Object> attributes) {}

  /** Extract and set the DestinationAddress field. */
  private void setDestinationAddress(
      EvaluateArgs<ReqT, RespT> args, Map<String, Object> attributes) 
      throws IllegalArgumentException {
    String destinationAddress = args.getCall().getAttributes()
        .get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR).toString();
    if (destinationAddress == null || destinationAddress.length() == 0) {
      throw new IllegalArgumentException("DestinationAddress field is not found. ");
    }
    attributes.put("destinationAddress", destinationAddress);
  }

  // TBD
  // private void setDestinationPort(
  //    EvaluateArgs<ReqT, RespT> args, Map<String, Object> attributes) {}

  /** Extract and set the ConnectionRequestedServerName field. */
  private void setConnectionRequestedServerName(
      EvaluateArgs<ReqT, RespT> args, Map<String, Object> attributes) 
      throws IllegalArgumentException {
    String connectionRequestedServerName = args.getCall().getAuthority();
    if (connectionRequestedServerName == null || connectionRequestedServerName.length() == 0) {
      throw new IllegalArgumentException("ConnectionRequestedServerName field is not found. ");
    }
    attributes.put("connectionRequestedServerName", connectionRequestedServerName);
  }

  // TBD
  // private void setConnectionUriSanPeerCertificate(
  //    EvaluateArgs<ReqT, RespT> args, Map<String, Object> attributes) {}
}
