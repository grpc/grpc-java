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

package io.grpc.xds.internal.rbac.engine.cel;

import static com.google.common.truth.Truth.assertThat;

import com.google.api.expr.v1alpha1.Expr;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Descriptors.Descriptor;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for CEL library stub. */
@RunWith(JUnit4.class)
public class CelInterfaceTest {
  private RuntimeTypeProvider messageProvider;
  private Dispatcher dispatcher;
  private Interpreter interpreter;
  private Activation activation;
  private String activationString;
  private Object activationResolution;
  private Object result;
  private InterpreterException interpreterException;
  private InterpreterException.Builder interpreterExceptionBuilder;

  @Test
  public void testCelInterface() throws Exception {
    // Set up interpreter used in Cel library's eval function.
    List<Descriptor> descriptors = new ArrayList<>();
    messageProvider = DescriptorMessageProvider.dynamicMessages(descriptors);
    dispatcher = DefaultDispatcher.create();
    interpreter = new DefaultInterpreter(messageProvider, dispatcher);
    // Set up activation used in Cel library's eval function.
    Map<String, Object> map = new HashMap<>();
    map.put("requestUrlPath", new Object());
    map.put("requestHost", new Object());
    map.put("requestMethod", new Object());
    ImmutableMap<String, Object> apiAttributes = ImmutableMap.copyOf(map);
    activation = Activation.copyOf(apiAttributes);
    activationString = activation.toString();
    activationResolution = activation.resolve("");
    // Add a fake condition Expr that are being evaluated.
    Expr conditions = Expr.newBuilder().build();
    result = interpreter.createInterpretable(conditions).eval(activation);
    assertThat(messageProvider).isNotNull();
    assertThat(dispatcher).isNotNull();
    assertThat(interpreter).isNotNull();
    assertThat(activation).isNotNull();
    assertThat(activationString).isNotNull();
    assertThat(activationResolution).isNull();
    assertThat(result).isNotNull();
  }

  @Test
  public void testInterpreterException() {
    interpreterExceptionBuilder = new InterpreterException.Builder("");
    interpreterException = interpreterExceptionBuilder.build();
    assertThat(interpreterExceptionBuilder).isNotNull();
    assertThat(interpreterException).isNotNull();
  }
}
