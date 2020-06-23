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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.api.expr.v1alpha1.Expr;
import com.google.common.collect.ImmutableMap;
import io.grpc.xds.XdsCelLibrariesDefault;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.rules.ExpectedException;
import org.junit.runners.JUnit4;

/** Unit tests for {@link XdsCelLibrariesDefault} */
public class XdsCelLibrariesDefaultTest {
  private XdsCelLibraries.RuntimeTypeProvider messageProvider;
  private XdsCelLibraries.Dispatcher dispatcher;
  private XdsCelLibraries.Interpreter interpreter;
  private XdsCelLibraries.Activation activation;
  private Object result;

  @Test
  public void setup() {
    List<Descriptor> descriptors = new ArrayList<>();
    messageProvider = XdsCelLibrariesDefault.DescriptorMessageProvider.dynamicMessages(descriptors);
    dispatcher = XdsCelLibrariesDefault.DefaultDispatcher.create();
    interpreter = new XdsCelLibrariesDefault.DefaultInterpreter(messageProvider, dispatcher);

    Expr checkedResult = Expr.newBuilder().build();
    Map<String, Object> map = new HashMap<>();
    map.put("requestUrlPath", new Object());
    map.put("requestHost", new Object());
    map.put("requestMethod", new Object());
    map.put("requestHeaders", new Object());
    map.put("sourceAddress", new Object());
    map.put("sourcePort", new Object());
    map.put("destinationAddress", new Object());

    ImmutableMap<String, Object> apiAttributes = ImmutableMap.copyOf(map);

    activation = XdsCelLibraries.Activation.copyOf(apiAttributes);
    result = interpreter.createInterpretable(checkedResult).eval(activation);

    assertThat(messageProvider).isNotNull();
    assertThat(dispatcher).isNotNull();
    assertThat(interpreter).isNotNull();
    assertThat(activation).isNotNull();
    assertThat(result).isNotNull();
  }
}