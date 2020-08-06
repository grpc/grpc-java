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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.google.api.expr.v1alpha1.Expr;
import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.config.rbac.v2.RBAC;
import io.grpc.xds.internal.rbac.engine.cel.Activation;
import io.grpc.xds.internal.rbac.engine.cel.Dispatcher;
import io.grpc.xds.internal.rbac.engine.cel.Interpretable;
import io.grpc.xds.internal.rbac.engine.cel.Interpreter;
import io.grpc.xds.internal.rbac.engine.cel.InterpreterException;
import io.grpc.xds.internal.rbac.engine.cel.RuntimeTypeProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for CEL library stub. */
@RunWith(JUnit4.class)
public class CelExpressionEvaluationTest<ReqT, RespT> {
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
  
  private Expr expr;
  private Object result;
  private AuthorizationEngine<ReqT,RespT> engine;

  @Before
  public void setup() throws InterpreterException {
    RBAC rbacAllow = RBAC.newBuilder()
        .setAction(RBAC.Action.ALLOW)
        .build();
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacAllow}));
    engine = new AuthorizationEngine<>(ImmutableList.copyOf(rbacList));
    when(interpretable.eval(any(Activation.class))).thenReturn(true);
  }

  @Test
  public void testCelInterface() throws InterpreterException {
    expr = Expr.newBuilder().build();
    result = engine.matches(expr, activation);
    assertThat(messageProvider).isNotNull();
    assertThat(dispatcher).isNotNull();
    assertThat(interpreter).isNotNull();
    assertThat(activation).isNotNull();
    assertThat(result).isNotNull();
  }
}
