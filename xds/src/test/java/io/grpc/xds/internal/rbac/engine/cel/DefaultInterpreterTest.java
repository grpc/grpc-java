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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.api.expr.v1alpha1.CheckedExpr;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Descriptors.Descriptor;
import io.grpc.Metadata;
import io.grpc.xds.InterpreterException;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.Test;

/** Unit tests for {@link DefaultInterpreter}. */
@RunWith(JUnit4.class)
public class DefaultInterpreterTest {

  @Test
  public void setup() throws InterpreterException {
    List<Descriptor> descriptors = new ArrayList<>();
    RuntimeTypeProvider messageProvider = DescriptorMessageProvider.dynamicMessages(descriptors);
    Dispatcher dispatcher = DefaultDispatcher.create();
    Interpreter interpreter = new DefaultInterpreter(messageProvider, dispatcher);
    CheckedExpr checkedResult = CheckedExpr.newBuilder().build();

    Map<String, Object> map = new HashMap<>();
    map.put("requestUrlPath", new Object());
    map.put("requestHost", new Object());
    map.put("requestMethod", new Object());
    map.put("requestHeaders", new Object());
    map.put("sourceAddress", new Object());
    map.put("sourcePort", new Object());
    map.put("destinationAddress", new Object());

    ImmutableMap<String, Object> apiAttributes = ImmutableMap.copyOf(map);

    Activation activation = Activation.copyOf(apiAttributes);
    Object result = interpreter.createInterpretable(checkedResult).eval(activation);
  }
}