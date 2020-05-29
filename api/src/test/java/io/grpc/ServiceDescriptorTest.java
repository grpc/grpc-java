/*
 * Copyright 2017 The gRPC Authors
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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.MethodDescriptor.MethodType;
import io.grpc.testing.TestMethodDescriptors;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ServiceDescriptor}.
 */
@RunWith(JUnit4.class)
public class ServiceDescriptorTest {

  @Test
  public void failsOnNullName() {
    try {
      new ServiceDescriptor(null, Collections.<MethodDescriptor<?, ?>>emptyList());
      Assert.fail();
    } catch (NullPointerException ex) {
      Assert.assertEquals("name", ex.getMessage());
    }
  }

  @Test
  public void failsOnNullMethods() {
    try {
      new ServiceDescriptor("name", (Collection<MethodDescriptor<?, ?>>) null);
      Assert.fail();
    } catch (NullPointerException ex) {
      Assert.assertEquals("methods", ex.getMessage());
    }
  }

  @Test
  public void failsOnNullMethod() {
    try {
      new ServiceDescriptor("name", Collections.<MethodDescriptor<?, ?>>singletonList(null));
      Assert.fail();
    } catch (NullPointerException ex) {
      Assert.assertEquals("method", ex.getMessage());
    }
  }

  @Test
  public void failsOnNonMatchingNames() {
    List<MethodDescriptor<?, ?>> descriptors = Collections.<MethodDescriptor<?, ?>>singletonList(
        MethodDescriptor.<Void, Void>newBuilder()
          .setType(MethodType.UNARY)
          .setFullMethodName(MethodDescriptor.generateFullMethodName("wrongservice", "method"))
          .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
          .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
          .build());

    try {
      new ServiceDescriptor("name", descriptors);
      Assert.fail();
    } catch (IllegalArgumentException ex) {
      Assert.assertEquals("service names wrongservice != name", ex.getMessage());
    }
  }

  @Test
  public void failsOnNonDuplicateNames() {
    List<MethodDescriptor<?, ?>> descriptors = Arrays.<MethodDescriptor<?, ?>>asList(
        MethodDescriptor.<Void, Void>newBuilder()
          .setType(MethodType.UNARY)
          .setFullMethodName(MethodDescriptor.generateFullMethodName("name", "method"))
          .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
          .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
          .build(),
        MethodDescriptor.<Void, Void>newBuilder()
          .setType(MethodType.UNARY)
          .setFullMethodName(MethodDescriptor.generateFullMethodName("name", "method"))
          .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
          .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
          .build());

    try {
      new ServiceDescriptor("name", descriptors);
      fail();
    } catch (IllegalArgumentException ex) {
      Assert.assertEquals("duplicate name name/method", ex.getMessage());
    }
  }

  @Test
  public void toStringTest() {
    ServiceDescriptor descriptor = new ServiceDescriptor("package.service",
        Arrays.<MethodDescriptor<?, ?>>asList(
        MethodDescriptor.<Void, Void>newBuilder()
          .setType(MethodType.UNARY)
          .setFullMethodName(MethodDescriptor.generateFullMethodName("package.service",
            "methodOne"))
          .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
          .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
          .build(),
        MethodDescriptor.<Void, Void>newBuilder()
          .setType(MethodType.UNARY)
          .setFullMethodName(MethodDescriptor.generateFullMethodName("package.service",
            "methodTwo"))
          .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
          .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
          .build()));

    String toString = descriptor.toString();
    assertTrue(toString.contains("ServiceDescriptor"));
    assertTrue(toString.contains("name=package.service"));
    assertTrue(toString.contains("package.service/methodOne"));
    assertTrue(toString.contains("package.service/methodTwo"));
  }
}
