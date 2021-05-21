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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.google.api.expr.v1alpha1.Expr;
import com.google.api.expr.v1alpha1.Expr.Ident;
import com.google.common.collect.ImmutableMap;
import io.envoyproxy.envoy.config.rbac.v2.Policy;
import io.envoyproxy.envoy.config.rbac.v2.RBAC;
import io.envoyproxy.envoy.config.rbac.v2.RBAC.Action;
import io.grpc.xds.internal.rbac.engine.cel.Activation;
import io.grpc.xds.internal.rbac.engine.cel.InterpreterException;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for evaluate function of CEL Evaluation Engine. */
@RunWith(JUnit4.class)
public class AuthzEngineEvaluationTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  
  @Mock
  private EvaluateArgs args;
  @Mock
  private Map<String, Object> attributes;

  private AuthorizationEngine spyEngine;
  private AuthorizationDecision evaluateResult;

  // Mock RBAC engine with ALLOW action.
  private RBAC rbacAllow;
  // Mock RBAC engine with DENY action.
  private RBAC rbacDeny;

  // Mock policies that will be used to construct RBAC Engine.
  private Policy policy1;
  private Policy policy2;
  private Policy policy3;
  private Policy policy4;
  private Policy policy5;
  private Policy policy6;

  // Mock conditions that will be used to construct RBAC poilcies.
  private Expr condition1;
  private Expr condition2;
  private Expr condition3;
  private Expr condition4;
  private Expr condition5;
  private Expr condition6;
  
  @Before
  public void buildRbac() {
    // Set up RBAC conditions.
    condition1 = Expr.newBuilder()
        .setIdentExpr(Ident.newBuilder().setName("Condition 1").build())
        .build();
    condition2 = Expr.newBuilder()
        .setIdentExpr(Ident.newBuilder().setName("Condition 2").build())
        .build();
    condition3 = Expr.newBuilder()
        .setIdentExpr(Ident.newBuilder().setName("Condition 3").build())
        .build();
    condition4 = Expr.newBuilder()
        .setIdentExpr(Ident.newBuilder().setName("Condition 4").build())
        .build();
    condition5 = Expr.newBuilder()
        .setIdentExpr(Ident.newBuilder().setName("Condition 5").build())
        .build();
    condition6 = Expr.newBuilder()
        .setIdentExpr(Ident.newBuilder().setName("Condition 6").build())
        .build();
    // Set up RBAC policies.
    policy1 = Policy.newBuilder().setCondition(condition1).build();
    policy2 = Policy.newBuilder().setCondition(condition2).build();
    policy3 = Policy.newBuilder().setCondition(condition3).build();
    policy4 = Policy.newBuilder().setCondition(condition4).build();
    policy5 = Policy.newBuilder().setCondition(condition5).build();
    policy6 = Policy.newBuilder().setCondition(condition6).build();
    // Set up RBACs.
    rbacAllow = RBAC.newBuilder()
        .setAction(Action.ALLOW)
        .putPolicies("Policy 1", policy1)
        .putPolicies("Policy 2", policy2)
        .putPolicies("Policy 3", policy3)
        .build();
    rbacDeny = RBAC.newBuilder()
        .setAction(Action.DENY)
        .putPolicies("Policy 4", policy4)
        .putPolicies("Policy 5", policy5)
        .putPolicies("Policy 6", policy6)
        .build();
  }

  /** Build an ALLOW engine from Policy 1, 2, 3. */
  @Before
  public void setupEngineSingleRbacAllow() {
    buildRbac();
    AuthorizationEngine engine = new AuthorizationEngine(rbacAllow);
    spyEngine = Mockito.spy(engine);
    doReturn(ImmutableMap.copyOf(attributes)).when(args).generateEnvoyAttributes();
  }

  /** Build a DENY engine from Policy 4, 5, 6. */
  @Before
  public void setupEngineSingleRbacDeny() {
    buildRbac();
    AuthorizationEngine engine = new AuthorizationEngine(rbacDeny);
    spyEngine = Mockito.spy(engine);
    doReturn(ImmutableMap.copyOf(attributes)).when(args).generateEnvoyAttributes();
  }

  /** Build a pair of engines with a DENY engine followed by an ALLOW engine. */
  @Before
  public void setupEngineRbacPair() {
    buildRbac();
    AuthorizationEngine engine = new AuthorizationEngine(rbacDeny, rbacAllow);
    spyEngine = Mockito.spy(engine);
    doReturn(ImmutableMap.copyOf(attributes)).when(args).generateEnvoyAttributes();
  }

  /**
   * Test on the ALLOW engine.
   * The evaluation result of all the CEL expressions is set to true,
   * so the gRPC authorization returns ALLOW.
   */
  @Test
  public void testAllowEngineWithAllMatchedPolicies() throws InterpreterException {
    setupEngineSingleRbacAllow();
    // Policy 1 - matched; Policy 2 - matched; Policy 3 - matched
    doReturn(true).when(spyEngine).matches(eq(condition1), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition2), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition3), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertThat(evaluateResult.getDecision()).isEqualTo(AuthorizationDecision.Output.ALLOW);
    assertThat(evaluateResult.getPolicyNames()).hasSize(1);
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 1");
  }

  /**
   * Test on the ALLOW engine.
   * The evaluation result of all the CEL expressions is set to false,
   * so the gRPC authorization returns DENY.
   */
  @Test
  public void testAllowEngineWithAllUnmatchedPolicies() throws InterpreterException {
    setupEngineSingleRbacAllow();
    // Policy 1 - unmatched; Policy 2 - unmatched; Policy 3 - unmatched
    doReturn(false).when(spyEngine).matches(eq(condition1), any(Activation.class));
    doReturn(false).when(spyEngine).matches(eq(condition2), any(Activation.class));
    doReturn(false).when(spyEngine).matches(eq(condition3), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertThat(evaluateResult.getDecision()).isEqualTo(AuthorizationDecision.Output.DENY);
    assertThat(evaluateResult.getPolicyNames()).isEmpty();
    assertThat(evaluateResult.toString()).isEqualTo(
        new StringBuilder("Authorization Decision: DENY. \n").toString());
  }

  /**
   * Test on the ALLOW engine.
   * The evaluation result of two CEL expressions is set to true,
   * and the evaluation result of one CEL expression is set to false,
   * so the gRPC authorization returns ALLOW.
   */
  @Test
  public void testAllowEngineWithMatchedAndUnmatchedPolicies() 
      throws InterpreterException {
    setupEngineSingleRbacAllow();
    // Policy 1 - unmatched; Policy 2 - matched; Policy 3 - matched
    doReturn(false).when(spyEngine).matches(eq(condition1), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition2), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition3), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertEquals(evaluateResult.getDecision(), AuthorizationDecision.Output.ALLOW);
    assertEquals(evaluateResult.getPolicyNames().size(), 1);
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 2");
  }

  /**
   * Test on the ALLOW engine.
   * The evaluation result of one CEL expression is set to unknown,
   * so the gRPC authorization returns UNKNOWN.
   */
  @Test
  public void testAllowEngineWithUnknownAndUnmatchedPolicies() 
      throws InterpreterException {
    setupEngineSingleRbacAllow();
    // Policy 1 - unmatched; Policy 2 - unknown; Policy 3 - unknown
    doReturn(false).when(spyEngine).matches(eq(condition1), any(Activation.class));
    doThrow(new InterpreterException.Builder("Unknown result").build())
        .when(spyEngine).matches(eq(condition2), any(Activation.class));
    doThrow(new InterpreterException.Builder("Unknown result").build())
        .when(spyEngine).matches(eq(condition3), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertThat(evaluateResult.getDecision()).isEqualTo(AuthorizationDecision.Output.UNKNOWN);
    assertThat(evaluateResult.getPolicyNames()).hasSize(2);
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 2");
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 3");
    assertThat(evaluateResult.toString()).isEqualTo(
        new StringBuilder("Authorization Decision: UNKNOWN. \n" 
            + "Policy 2; \n" + "Policy 3; \n").toString());
  }

  /**
   * Test on the ALLOW engine.
   * The evaluation result of one CEL expression is set to unknown,
   * so the gRPC authorization returns UNKNOWN.
   */
  @Test
  public void testAllowEngineWithMatchedUnmatchedAndUnknownPolicies() 
      throws InterpreterException {
    setupEngineSingleRbacAllow();
    // Policy 1 - unmatched; Policy 2 - matched; Policy 3 - unknown
    doReturn(false).when(spyEngine).matches(eq(condition1), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition2), any(Activation.class));
    doThrow(new InterpreterException.Builder("Unknown result").build())
        .when(spyEngine).matches(eq(condition3), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertThat(evaluateResult.getDecision()).isEqualTo(AuthorizationDecision.Output.ALLOW);
    assertThat(evaluateResult.getPolicyNames()).hasSize(1);
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 2");
    assertThat(evaluateResult.toString()).isEqualTo(
        new StringBuilder("Authorization Decision: ALLOW. \n" + "Policy 2; \n").toString());
  }

  /**
   * Test on the DENY engine.
   * The evaluation result of all the CEL expressions is set to true,
   * so the gRPC authorization returns DENY.
   */
  @Test
  public void testDenyEngineWithAllMatchedPolicies() throws InterpreterException {
    setupEngineSingleRbacDeny();
    // Policy 4 - matched; Policy 5 - matched; Policy 6 - matched
    doReturn(true).when(spyEngine).matches(eq(condition4), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition5), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition6), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertThat(evaluateResult.getDecision()).isEqualTo(AuthorizationDecision.Output.DENY);
    assertThat(evaluateResult.getPolicyNames()).hasSize(1);
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 4");
  }

  /**
   * Test on the DENY engine.
   * The evaluation result of all the CEL expressions is set to false,
   * so the gRPC authorization returns ALLOW.
   */
  @Test
  public void testDenyEngineWithAllUnmatchedPolicies() throws InterpreterException {
    setupEngineSingleRbacDeny();
    // Policy 4 - unmatched; Policy 5 - unmatched; Policy 6 - unmatched
    doReturn(false).when(spyEngine).matches(eq(condition4), any(Activation.class));
    doReturn(false).when(spyEngine).matches(eq(condition5), any(Activation.class));
    doReturn(false).when(spyEngine).matches(eq(condition6), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertThat(evaluateResult.getDecision()).isEqualTo(AuthorizationDecision.Output.ALLOW);
    assertThat(evaluateResult.getPolicyNames()).isEmpty();
  }

  /**
   * Test on the DENY engine.
   * The evaluation result of two CEL expressions is set to true,
   * and the evaluation result of one CEL expression is set to false,
   * so the gRPC authorization returns DENY.
   */
  @Test
  public void testDenyEngineWithMatchedAndUnmatchedPolicies() 
      throws InterpreterException {
    setupEngineSingleRbacDeny();
    // Policy 4 - unmatched; Policy 5 - matched; Policy 6 - matched
    doReturn(false).when(spyEngine).matches(eq(condition4), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition5), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition6), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertThat(evaluateResult.getDecision()).isEqualTo(AuthorizationDecision.Output.DENY);
    assertThat(evaluateResult.getPolicyNames()).hasSize(1);
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 5");
  }

  /**
   * Test on the DENY engine.
   * The evaluation result of one CEL expression is set to unknown,
   * so the gRPC authorization returns UNKNOWN.
   */
  @Test
  public void testDenyEngineWithUnknownAndUnmatchedPolicies() 
      throws InterpreterException {
    setupEngineSingleRbacDeny();
    // Policy 4 - unmatched; Policy 5 - unknown; Policy 6 - unknown
    doReturn(false).when(spyEngine).matches(eq(condition4), any(Activation.class));
    doThrow(new InterpreterException.Builder("Unknown result").build())
        .when(spyEngine).matches(eq(condition5), any(Activation.class));
    doThrow(new InterpreterException.Builder("Unknown result").build())
        .when(spyEngine).matches(eq(condition6), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertThat(evaluateResult.getDecision()).isEqualTo(AuthorizationDecision.Output.UNKNOWN);
    assertThat(evaluateResult.getPolicyNames()).hasSize(2);
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 5");
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 6");
  }

  /**
   * Test on the DENY engine.
   * The evaluation result of one CEL expression is set to unknown,
   * so the gRPC authorization returns UNKNOWN.
   */
  @Test
  public void testDenyEngineWithMatchedUnmatchedAndUnknownPolicies() 
      throws InterpreterException {
    setupEngineSingleRbacDeny();
    // Policy 4 - unmatched; Policy 5 - matched; Policy 6 - unknown
    doReturn(false).when(spyEngine).matches(eq(condition4), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition5), any(Activation.class));
    doThrow(new InterpreterException.Builder("Unknown result").build())
        .when(spyEngine).matches(eq(condition6), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertThat(evaluateResult.getDecision()).isEqualTo(AuthorizationDecision.Output.DENY);
    assertThat(evaluateResult.getPolicyNames()).hasSize(1);
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 5");
  }

  /**
   * Test on the DENY engine and ALLOW engine pair.
   * The evaluation result of all the CEL expressions is set to true in DENY engine,
   * so the gRPC authorization returns DENY.
   */
  @Test
  public void testEnginePairWithAllMatchedDenyEngine() throws InterpreterException {
    setupEngineRbacPair();
    // Policy 4 - matched; Policy 5 - matched; Policy 6 - matched
    // Policy 1 - matched; Policy 2 - matched; Policy 3 - matched
    doReturn(true).when(spyEngine).matches(eq(condition1), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition2), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition3), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition4), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition5), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition6), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertThat(evaluateResult.getDecision()).isEqualTo(AuthorizationDecision.Output.DENY);
    assertThat(evaluateResult.getPolicyNames()).hasSize(1);
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 4");
  }

  /**
   * Test on the DENY engine and ALLOW engine pair.
   * The evaluation result of two CEL expressions is set to true,
   * and the evaluation result of one CEL expression is set to false in DENY engine, 
   * so the gRPC authorization returns DENY.
   */
  @Test
  public void testEnginePairWithPartiallyMatchedDenyEngine() 
      throws InterpreterException {
    setupEngineRbacPair();
    // Policy 4 - unmatched; Policy 5 - matched; Policy 6 - unknown
    // Policy 1 - matched; Policy 2 - matched; Policy 3 - matched
    doReturn(true).when(spyEngine).matches(eq(condition1), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition2), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition3), any(Activation.class));
    doReturn(false).when(spyEngine).matches(eq(condition4), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition5), any(Activation.class));
    doThrow(new InterpreterException.Builder("Unknown result").build())
        .when(spyEngine).matches(eq(condition6), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertThat(evaluateResult.getDecision()).isEqualTo(AuthorizationDecision.Output.DENY);
    assertThat(evaluateResult.getPolicyNames()).hasSize(1);
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 5");
  }

  /**
   * Test on the DENY engine and ALLOW engine pair.
   * The DENY engine has unknown policies, so the gRPC authorization returns UNKNOWN.
   */
  @Test
  public void testEnginePairWithUnknownDenyEngine() throws InterpreterException {
    setupEngineRbacPair();
    // Policy 4 - unmatched; Policy 5 - unknown; Policy 6 - unknown
    // Policy 1 - matched; Policy 2 - matched; Policy 3 - matched
    doReturn(true).when(spyEngine).matches(eq(condition1), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition2), any(Activation.class));
    doReturn(true).when(spyEngine).matches(eq(condition3), any(Activation.class));
    doReturn(false).when(spyEngine).matches(eq(condition4), any(Activation.class));
    doThrow(new InterpreterException.Builder("Unknown result").build())
        .when(spyEngine).matches(eq(condition5), any(Activation.class));
    doThrow(new InterpreterException.Builder("Unknown result").build())
        .when(spyEngine).matches(eq(condition6), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertThat(evaluateResult.getDecision()).isEqualTo(AuthorizationDecision.Output.UNKNOWN);
    assertThat(evaluateResult.getPolicyNames()).hasSize(2);
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 5");
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 6");
  }

  /**
   * Test on the DENY engine and ALLOW engine pair.
   * The evaluation result of all the CEL expressions is set to false in DENY engine,
   * and the ALLOW engine has unknown policies, 
   * so the gRPC authorization returns UNKNOWN.
   */
  @Test
  public void testEnginePairWithUnmatchedDenyEngineAndUnknownAllowEngine() 
      throws InterpreterException {
    setupEngineRbacPair();
    // Policy 4 - unmatched; Policy 5 - unmatched; Policy 6 - unmatched
    // Policy 1 - unmatched; Policy 2 - unknown; Policy 3 - unknown
    doReturn(false).when(spyEngine).matches(eq(condition1), any(Activation.class));
    doThrow(new InterpreterException.Builder("Unknown result").build())
        .when(spyEngine).matches(eq(condition2), any(Activation.class));
    doThrow(new InterpreterException.Builder("Unknown result").build())
        .when(spyEngine).matches(eq(condition3), any(Activation.class));
    doReturn(false).when(spyEngine).matches(eq(condition4), any(Activation.class));
    doReturn(false).when(spyEngine).matches(eq(condition5), any(Activation.class));
    doReturn(false).when(spyEngine).matches(eq(condition6), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertThat(evaluateResult.getDecision()).isEqualTo(AuthorizationDecision.Output.UNKNOWN);
    assertThat(evaluateResult.getPolicyNames()).hasSize(2);
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 2");
    assertThat(evaluateResult.getPolicyNames()).contains("Policy 3");
  }

  /**
   * Test on the DENY engine and ALLOW engine pair.
   * The evaluation result of all the CEL expressions is set to false in both engines,
   * so the gRPC authorization returns DENY.
   */
  @Test
  public void testUnmatchedEnginePair() throws InterpreterException {
    setupEngineRbacPair();
    // Policy 4 - unmatched; Policy 5 - unmatched; Policy 6 - unmatched
    // Policy 1 - unmatched; Policy 2 - unmatched; Policy 3 - unmatched
    doReturn(false).when(spyEngine).matches(eq(condition1), any(Activation.class));
    doReturn(false).when(spyEngine).matches(eq(condition2), any(Activation.class));
    doReturn(false).when(spyEngine).matches(eq(condition3), any(Activation.class));
    doReturn(false).when(spyEngine).matches(eq(condition4), any(Activation.class));
    doReturn(false).when(spyEngine).matches(eq(condition5), any(Activation.class));
    doReturn(false).when(spyEngine).matches(eq(condition6), any(Activation.class));
    evaluateResult = spyEngine.evaluate(args);
    assertThat(evaluateResult.getDecision()).isEqualTo(AuthorizationDecision.Output.DENY);
    assertThat(evaluateResult.getPolicyNames()).isEmpty();
  }
}
