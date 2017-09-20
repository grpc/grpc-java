/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

import com.google.common.base.Defaults;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * An abstract base class for testing forwarding classes. This automatically checks that
 * all public methods of the {@link #delegateClass()} are forwarded by the forwarder to the
 * delegate. This does NOT verify that arguments are forwarded properly. It only alerts
 * the developer if a forward method is missing.
 */
@RunWith(JUnit4.class)
public abstract class AbstractForwardingTest<T> {
  /**
   * Returns a mock object that can be used with {@code Mockito.verify()}.
   */
  public abstract T mockDelegate();

  /**
   * Returns a forwarder object that wraps around the object returned by {@link #mockDelegate()}.
   */
  public abstract T forwarder();

  /**
   * Returns the class of the object being forwarded. There is no easy way to find this
   * reflectively from the value of {@link #mockDelegate()}.
   * {@code mockDelegate().getClass().getSuperclass()} works at the moment but this would rely on
   * mockito internals. It's easier to explicitly specify the class.
   */
  public abstract Class<T> delegateClass();

  @Test
  public void methodsForwarded() throws Exception {
    assertTrue(mockingDetails(mockDelegate()).isMock());
    assertFalse(mockingDetails(forwarder()).isMock());

    for (Method method : delegateClass().getDeclaredMethods()) {
      if (Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())) {
        continue;
      }
      Class<?>[] argTypes = method.getParameterTypes();
      Object[] args = new Object[argTypes.length];
      for (int i = 0; i < argTypes.length; i++) {
        args[i] = Defaults.defaultValue(argTypes[i]);
      }
      method.invoke(forwarder(), args);
      try {
        method.invoke(verify(mockDelegate()), args);
      } catch (InvocationTargetException e) {
        // Print the method name at the top to be more readable
        throw new InvocationTargetException(
            e, String.format("Method was not forwarded: %s", method));
      }
    }
  }
}
