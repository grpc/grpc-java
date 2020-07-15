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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.google.api.expr.v1alpha1.Expr;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.envoyproxy.envoy.config.rbac.v2.Policy;
import io.envoyproxy.envoy.config.rbac.v2.RBAC;
import io.envoyproxy.envoy.config.rbac.v2.RBAC.Action;
import io.grpc.xds.internal.cel.Activation;
import io.grpc.xds.internal.cel.InterpreterException;
import java.lang.StringBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for evaluate function of Cel Evaluation Engine. */
@RunWith(JUnit4.class)
public class CelEvaluationTest<ReqT, RespT> {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  
  @Mock
  private EvaluateArgs<ReqT,RespT> args;

  @Mock
  private Activation activation;

  private CelEvaluationEngine<ReqT,RespT> engine;
  private CelEvaluationEngine<ReqT,RespT> spyEngine;
  private Expr condition;
  private RBAC rbacAllow;
  private RBAC rbacDeny;
  private Map<String, Object> attributes;

  @Before
  public void setup() {
    condition = Expr.newBuilder().build();
    attributes = new HashMap<>();
    rbacAllow = RBAC.newBuilder()
        .setAction(Action.ALLOW)
        .putPolicies("Policy 1", Policy.newBuilder().setCondition(condition).build())
        .build();
    rbacDeny = RBAC.newBuilder()
        .setAction(Action.DENY)
        .putPolicies("Policy 1", Policy.newBuilder().setCondition(condition).build())
        .build();
  }

  @Test
  public void testEvaluateMatchAllow() throws InterpreterException {
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacAllow}));
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    spyEngine = Mockito.spy(engine);
    doReturn(ImmutableMap.copyOf(attributes)).when(spyEngine).extractFields(
        ArgumentMatchers.<EvaluateArgs<ReqT,RespT>>any());
    when(spyEngine.matches(any(Expr.class), any(Activation.class))).thenReturn(true);
    assertEquals(spyEngine.evaluate(args).getDecision(), AuthorizationDecision.Decision.ALLOW);
    assertEquals(spyEngine.evaluate(args).getMatchingPolicyNames().get(0), "Policy 1");
    assertEquals(spyEngine.evaluate(args).toString(), 
        new StringBuilder("Authorization Decision: ALLOW. \n" + "Policy 1; \n").toString());
  }

  @Test
  public void testEvaluateMatchDeny() throws InterpreterException {
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacDeny}));
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    spyEngine = Mockito.spy(engine);
    doReturn(ImmutableMap.copyOf(attributes)).when(spyEngine).extractFields(
        ArgumentMatchers.<EvaluateArgs<ReqT,RespT>>any());
    when(spyEngine.matches(any(Expr.class), any(Activation.class))).thenReturn(true);
    assertEquals(spyEngine.evaluate(args).getDecision(), AuthorizationDecision.Decision.DENY);
    assertEquals(spyEngine.evaluate(args).getMatchingPolicyNames().get(0), "Policy 1");
    assertEquals(spyEngine.evaluate(args).toString(), 
        new StringBuilder("Authorization Decision: DENY. \n" + "Policy 1; \n").toString());
  }

  @Test
  public void testEvaluateNotMatchAllow() throws InterpreterException {
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacAllow}));
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    spyEngine = Mockito.spy(engine);
    doReturn(ImmutableMap.copyOf(attributes)).when(spyEngine).extractFields(
        ArgumentMatchers.<EvaluateArgs<ReqT,RespT>>any());
    when(spyEngine.matches(any(Expr.class), any(Activation.class))).thenReturn(false);
    assertEquals(spyEngine.evaluate(args).getDecision(), AuthorizationDecision.Decision.DENY);
    assertEquals(spyEngine.evaluate(args).getMatchingPolicyNames().size(), 0);
    assertEquals(spyEngine.evaluate(args).toString(), 
        new StringBuilder("Authorization Decision: DENY. \n").toString());
  }

  @Test
  public void testEvaluateNotMatchDeny() throws InterpreterException {
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacDeny}));
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    spyEngine = Mockito.spy(engine);
    doReturn(ImmutableMap.copyOf(attributes)).when(spyEngine).extractFields(
        ArgumentMatchers.<EvaluateArgs<ReqT,RespT>>any());
    when(spyEngine.matches(any(Expr.class), any(Activation.class))).thenReturn(false);
    assertEquals(spyEngine.evaluate(args).getDecision(), AuthorizationDecision.Decision.ALLOW);
    assertEquals(spyEngine.evaluate(args).getMatchingPolicyNames().size(), 0);
    assertEquals(spyEngine.evaluate(args).toString(), 
        new StringBuilder("Authorization Decision: ALLOW. \n").toString());
  }

  @Test
  public void testEvaluateUnknown() throws InterpreterException {
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacAllow}));
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    spyEngine = Mockito.spy(engine);
    doReturn(ImmutableMap.copyOf(attributes)).when(spyEngine).extractFields(
        ArgumentMatchers.<EvaluateArgs<ReqT,RespT>>any());
    when(spyEngine.matches(any(Expr.class), any(Activation.class))).thenThrow(
        new InterpreterException.Builder("Unknown result").build());
    assertEquals(spyEngine.evaluate(args).getDecision(), AuthorizationDecision.Decision.UNKNOWN);
    assertEquals(spyEngine.evaluate(args).getMatchingPolicyNames().get(0), "Policy 1");
    assertEquals(spyEngine.evaluate(args).toString(), 
        new StringBuilder("Authorization Decision: UNKNOWN. \n" + "Policy 1; \n").toString());
  }

  @Test
  public void testEvaluateRbacPairMatch() throws InterpreterException {
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacDeny, rbacAllow}));
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    spyEngine = Mockito.spy(engine);
    doReturn(ImmutableMap.copyOf(attributes)).when(spyEngine).extractFields(
        ArgumentMatchers.<EvaluateArgs<ReqT,RespT>>any());
    when(spyEngine.matches(any(Expr.class), any(Activation.class))).thenReturn(true);
    assertEquals(spyEngine.evaluate(args).getDecision(), AuthorizationDecision.Decision.DENY);
    assertEquals(spyEngine.evaluate(args).getMatchingPolicyNames().get(0), "Policy 1");
    assertEquals(spyEngine.evaluate(args).toString(), 
        new StringBuilder("Authorization Decision: DENY. \n" + "Policy 1; \n").toString());
  }

  @Test
  public void testEvaluateRbacPairNotMatch() throws InterpreterException {
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacDeny, rbacAllow}));
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    spyEngine = Mockito.spy(engine);
    doReturn(ImmutableMap.copyOf(attributes)).when(spyEngine).extractFields(
        ArgumentMatchers.<EvaluateArgs<ReqT,RespT>>any());
    when(spyEngine.matches(any(Expr.class), any(Activation.class))).thenReturn(false);
    assertEquals(spyEngine.evaluate(args).getDecision(), AuthorizationDecision.Decision.DENY);
    assertEquals(spyEngine.evaluate(args).getMatchingPolicyNames().size(), 0);
    assertEquals(spyEngine.evaluate(args).toString(), 
        new StringBuilder("Authorization Decision: DENY. \n").toString());
  }

  @Test
  public void testEvaluateRbacPairUnknown() throws InterpreterException {
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacDeny, rbacAllow}));
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    spyEngine = Mockito.spy(engine);
    doReturn(ImmutableMap.copyOf(attributes)).when(spyEngine).extractFields(
        ArgumentMatchers.<EvaluateArgs<ReqT,RespT>>any());
    when(spyEngine.matches(any(Expr.class), any(Activation.class)))
        .thenThrow(new InterpreterException.Builder("Unknown result").build());
    assertEquals(spyEngine.evaluate(args).getDecision(), AuthorizationDecision.Decision.UNKNOWN);
    assertEquals(spyEngine.evaluate(args).getMatchingPolicyNames().get(0), "Policy 1");
  }
}
