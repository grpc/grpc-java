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

package io.grpc.testing;

import com.google.common.io.ByteStreams;
import com.google.common.primitives.Longs;
import io.grpc.ExperimentalApi;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A collection of method descriptor constructors useful for tests.  These are useful if you need
 * a descriptor, but don't really care how it works.
 *
 * @since 1.1.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2600")
public final class TestMethodDescriptors {
  private TestMethodDescriptors() {}

  /**
   * Creates a new method descriptor that always creates zero length messages, and always parses to
   * null objects.
   *
   * @since 1.1.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2600")
  public static MethodDescriptor<Void, Void> voidMethod() {
    return TestMethodDescriptors.<Void, Void>noopMethod();
  }

  /**
   * Creates a new unary method descriptor that always creates zero length messages, always parses
   * to null objects, and always has the same method name.
   *
   * @since 1.1.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2600")
  public static <ReqT, RespT> MethodDescriptor<ReqT, RespT> noopMethod() {
    return noopMethod("service_foo", "method_bar");
  }

  private static <ReqT, RespT> MethodDescriptor<ReqT, RespT> noopMethod(
      String serviceName, String methodName) {
    return MethodDescriptor.<ReqT, RespT>newBuilder()
        .setType(MethodType.UNARY)
        .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, methodName))
        .setRequestMarshaller(TestMethodDescriptors.<ReqT>noopMarshaller())
        .setResponseMarshaller(TestMethodDescriptors.<RespT>noopMarshaller())
        .build();
  }

  /**
   * Creates a new marshaller that does nothing.
   *
   * @since 1.1.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2600")
  public static MethodDescriptor.Marshaller<Void> voidMarshaller() {
    return TestMethodDescriptors.<Void>noopMarshaller();
  }

  /**
   * Creates a new marshaller that does nothing.
   *
   * @since 1.1.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2600")
  public static <T> MethodDescriptor.Marshaller<T> noopMarshaller() {
    return new NoopMarshaller<T>();
  }

  private static final class NoopMarshaller<T> implements MethodDescriptor.Marshaller<T> {
    @Override
    public InputStream stream(T value) {
      return new ByteArrayInputStream(new byte[]{});
    }

    @Override
    public T parse(InputStream stream) {
      return null;
    }
  }

  /**
   * Creates a new method descriptor of the specified type. Each returned instance has a different
   * method name. The restriction for using this method descriptor is
   *
   * <ul>
   *   <li>it must be used for InProcess transports;</li>
   *   <li>the request and response message objects must not be mutated after being sent out or
   *       received.</li>
   * </ul>
   *
   * @since 1.5.0
   */
  @ExperimentalApi // TODO(zdapeng): tracking url
  public static <ReqT, RespT> MethodDescriptor<ReqT, RespT> inProcessGenericMethod(
      MethodType methodType) {
    return MethodDescriptor.<ReqT, RespT>newBuilder()
        .setType(methodType)
        .setFullMethodName(MethodDescriptor.generateFullMethodName(
            "serviceName_" + UUID.randomUUID(), "methodName_" + UUID.randomUUID()))
        .setRequestMarshaller(new InProcessMarshaller<ReqT>())
        .setResponseMarshaller(new InProcessMarshaller<RespT>())
        .build();
  }

  private static final class InProcessMarshaller<T> implements MethodDescriptor.Marshaller<T> {
    final Map<Long, T> map = new HashMap<Long, T>();
    long last;

    @Override
    public synchronized InputStream stream(T value) {
      final long id = last++;
      map.put(id, value);
      return new ByteArrayInputStream(Longs.toByteArray(id)) {
        @Override
        public void close() throws IOException {
          synchronized (InProcessMarshaller.this) {
            map.remove(id);
          }
        }
      };
    }

    @Override
    public synchronized T parse(InputStream stream) {
      try {
        return map.get(Longs.fromByteArray(ByteStreams.toByteArray(stream)));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
