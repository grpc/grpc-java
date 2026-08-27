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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.truth.StringSubject;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.testing.TestMethodDescriptors;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
    List<MethodDescriptor<?, ?>> methods = Collections.emptyList();
    NullPointerException e = assertThrows(NullPointerException.class,
        () -> new ServiceDescriptor(null, methods));
    assertThat(e).hasMessageThat().isEqualTo("name");
  }

  @Test
  public void failsOnNullMethods() {
    NullPointerException e = assertThrows(NullPointerException.class,
        () -> new ServiceDescriptor("name", (Collection<MethodDescriptor<?, ?>>) null));
    assertThat(e).hasMessageThat().isEqualTo("methods");
  }

  @Test
  public void failsOnNullMethod() {
    List<MethodDescriptor<?, ?>> methods = Collections.singletonList(null);
    NullPointerException e = assertThrows(NullPointerException.class,
        () -> new ServiceDescriptor("name", methods));
    assertThat(e).hasMessageThat().isEqualTo("method");
  }

  @Test
  public void failsOnNonMatchingNames() {
    List<MethodDescriptor<?, ?>> descriptors = Collections.<MethodDescriptor<?, ?>>singletonList(
        MethodDescriptor.<Void, Void>newBuilder()
          .setType(MethodType.UNARY)
          .setFullMethodName(MethodDescriptor.generateFullMethodName("wrongService", "method"))
          .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
          .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
          .build());

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> new ServiceDescriptor("fooService", descriptors));
    StringSubject error = assertThat(e).hasMessageThat();
    error.contains("service names");
    error.contains("fooService");
    error.contains("wrongService");
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

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> new ServiceDescriptor("name", descriptors));
    assertThat(e).hasMessageThat().isEqualTo("duplicate name name/method");
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
