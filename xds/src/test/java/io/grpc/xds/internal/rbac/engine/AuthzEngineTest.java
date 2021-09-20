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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.google.api.expr.v1alpha1.Expr;
import io.envoyproxy.envoy.config.rbac.v2.RBAC;
import io.envoyproxy.envoy.config.rbac.v2.RBAC.Action;
import io.grpc.xds.internal.rbac.engine.cel.Activation;
import io.grpc.xds.internal.rbac.engine.cel.Dispatcher;
import io.grpc.xds.internal.rbac.engine.cel.Interpretable;
import io.grpc.xds.internal.rbac.engine.cel.Interpreter;
import io.grpc.xds.internal.rbac.engine.cel.InterpreterException;
import io.grpc.xds.internal.rbac.engine.cel.RuntimeTypeProvider;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for constructor of CEL-based Authorization Engine. */
@RunWith(JUnit4.class)
public class AuthzEngineTest {
  @SuppressWarnings("deprecation") // https://github.com/grpc/grpc-java/issues/7467
  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private Activation activation;

  @Mock
  private RuntimeTypeProvider messageProvider;

  @Mock
  private Dispatcher dispatcher;

  @Mock
  private Interpreter interpreter;

  @Mock
  private Interpretable interpretable;
  
  private AuthorizationEngine engine;  
  private RBAC rbacDeny;
  private RBAC rbacAllow;
  private Object result;

  @Before
  public void setup() {
    rbacAllow = RBAC.newBuilder()
        .setAction(Action.ALLOW)
        .build();
    rbacDeny = RBAC.newBuilder()
        .setAction(Action.DENY)
        .build();
  }

  @Test
  public void createEngineAllowPolicy() {
    engine = new AuthorizationEngine(rbacAllow);
    assertNotNull(engine);
  }

  @Test
  public void createEngineDenyPolicy() {
    engine = new AuthorizationEngine(rbacDeny);
    assertNotNull(engine);
  }

  @Test
  public void createEngineDenyAllowPolicies() {
    engine = new AuthorizationEngine(rbacDeny, rbacAllow);
    assertNotNull(engine);
  }

  @Test
  public void failToCreateEngineIfRbacPairOfAllowAllow() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid RBAC list, " 
        + "must provide a RBAC with DENY action followed by a RBAC with ALLOW action. ");
    engine = new AuthorizationEngine(rbacAllow, rbacAllow);
    assertNull(engine);
  }

  @Test
  public void failToCreateEngineIfRbacPairOfAllowDeny() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid RBAC list, " 
        + "must provide a RBAC with DENY action followed by a RBAC with ALLOW action. ");
    engine = new AuthorizationEngine(rbacAllow, rbacDeny);
    assertNull(engine);
  }

  @Test
  public void failToCreateEngineIfRbacPairOfDenyDeny() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid RBAC list, " 
        + "must provide a RBAC with DENY action followed by a RBAC with ALLOW action. ");
    engine = new AuthorizationEngine(rbacDeny, rbacDeny);
    assertNull(engine);
  }

  @Test
  public void testCelInterface() throws InterpreterException {
    engine = new AuthorizationEngine(rbacAllow);
    when(interpretable.eval(any(Activation.class))).thenReturn(true);
    Expr expr = Expr.getDefaultInstance();
    result = engine.matches(expr, activation);
    assertThat(messageProvider).isNotNull();
    assertThat(dispatcher).isNotNull();
    assertThat(interpreter).isNotNull();
    assertThat(activation).isNotNull();
    assertThat(result).isNotNull();
  }
}
