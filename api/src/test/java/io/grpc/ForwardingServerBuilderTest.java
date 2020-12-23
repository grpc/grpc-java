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

package io.grpc;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.google.common.base.Defaults;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link ForwardingServerBuilder}.
 */
@RunWith(JUnit4.class)
public class ForwardingServerBuilderTest {
  private final ServerBuilder<?> mockDelegate = mock(ServerBuilder.class);
  private final ForwardingServerBuilder<?> testServerBuilder = new TestBuilder();

  private final class TestBuilder extends ForwardingServerBuilder<TestBuilder> {
    @Override
    protected ServerBuilder<?> delegate() {
      return mockDelegate;
    }
  }

  @Test
  public void allMethodsForwarded() throws Exception {
    ForwardingTestUtil.testMethodsForwarded(
        ServerBuilder.class,
        mockDelegate,
        testServerBuilder,
        Collections.<Method>emptyList());
  }

  @Test
  public void allBuilderMethodsReturnThis() throws Exception {
    for (Method method : ServerBuilder.class.getDeclaredMethods()) {
      if (Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())) {
        continue;
      }
      if (method.getName().equals("build")) {
        continue;
      }
      Class<?>[] argTypes = method.getParameterTypes();
      Object[] args = new Object[argTypes.length];
      for (int i = 0; i < argTypes.length; i++) {
        args[i] = Defaults.defaultValue(argTypes[i]);
      }

      Object returnedValue = method.invoke(testServerBuilder, args);

      assertThat(returnedValue).isSameInstanceAs(testServerBuilder);
    }
  }

  @Test
  public void buildReturnsDelegateBuildByDefault() {
    Server server = mock(Server.class);
    doReturn(server).when(mockDelegate).build();

    assertThat(testServerBuilder.build()).isSameInstanceAs(server);
  }
}
