/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc;

import io.grpc.MethodDescriptor.MethodType;
import io.grpc.testing.TestMethodDescriptors;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ServiceDescriptor}.
 */
@RunWith(JUnit4.class)
public class ServiceDescriptorTest {

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  @Test
  public void failsOnNullName() {
    thrown.expect(NullPointerException.class);
    thrown.expectMessage("name");

    new ServiceDescriptor(null, Collections.<MethodDescriptor<?, ?>>emptyList());
  }

  @Test
  public void failsOnNullMethods() {
    thrown.expect(NullPointerException.class);
    thrown.expectMessage("methods");

    new ServiceDescriptor("name", (Collection<MethodDescriptor<?, ?>>) null);
  }

  @Test
  public void failsOnNullMethod() {
    thrown.expect(NullPointerException.class);
    thrown.expectMessage("method");

    new ServiceDescriptor("name", (Collections.<MethodDescriptor<?, ?>>singletonList(null)));
  }

  @Test
  public void failsOnNonMatchingNames() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("service names");

    new ServiceDescriptor("name", Collections.<MethodDescriptor<?, ?>>singletonList(
        MethodDescriptor.create(
            MethodType.UNARY,
            MethodDescriptor.generateFullMethodName("wrongservice", "method"),
            TestMethodDescriptors.voidMarshaller(),
            TestMethodDescriptors.voidMarshaller())));
  }

  @Test
  public void failsOnNonDuplicateNames() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("duplicate");

    new ServiceDescriptor("name", Arrays.<MethodDescriptor<?, ?>>asList(
        MethodDescriptor.create(
            MethodType.UNARY,
            MethodDescriptor.generateFullMethodName("name", "method"),
            TestMethodDescriptors.voidMarshaller(),
            TestMethodDescriptors.voidMarshaller()),
        MethodDescriptor.create(
            MethodType.UNARY,
            MethodDescriptor.generateFullMethodName("name", "method"),
            TestMethodDescriptors.voidMarshaller(),
            TestMethodDescriptors.voidMarshaller())));
  }
}
