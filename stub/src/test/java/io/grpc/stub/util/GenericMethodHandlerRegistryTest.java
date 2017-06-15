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

package io.grpc.stub.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls.BidiStreamingMethod;
import io.grpc.stub.ServerCalls.ClientStreamingMethod;
import io.grpc.stub.ServerCalls.ServerStreamingMethod;
import io.grpc.stub.ServerCalls.UnaryMethod;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.TestMethodDescriptors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GenericMethodHandlerRegistryTest {
  @Test
  public void addUnaryMethod() {
    GenericMethodHandlerRegistry registry = new GenericMethodHandlerRegistry();
    ServerServiceDefinition svcDef = registry.addUnaryMethod(
        MethodDescriptor.<Void, Void>newBuilder()
            .setType(MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("foo", "bar"))
            .setRequestMarshaller(TestMethodDescriptors.<Void>noopMarshaller())
            .setResponseMarshaller(TestMethodDescriptors.<Void>noopMarshaller())
            .build(),
        new UnaryMethod<Void, Void>() {
          @Override public void invoke(Void request, StreamObserver<Void> responseObserver) {}
        });

    assertNull(svcDef);
    assertNotNull(registry.lookupMethod("foo/bar"));
  }

  @Test
  public void addServerStreamingMethod() {
    GenericMethodHandlerRegistry registry = new GenericMethodHandlerRegistry();
    ServerServiceDefinition svcDef = registry.addServerStreamingMethod(
        MethodDescriptor.<Void, Void>newBuilder()
            .setType(MethodType.SERVER_STREAMING)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("foo", "bar"))
            .setRequestMarshaller(TestMethodDescriptors.<Void>noopMarshaller())
            .setResponseMarshaller(TestMethodDescriptors.<Void>noopMarshaller())
            .build(),
        new ServerStreamingMethod<Void, Void>() {
          @Override public void invoke(Void request, StreamObserver<Void> responseObserver) {}
        });

    assertNull(svcDef);
    assertNotNull(registry.lookupMethod("foo/bar"));
  }

  @Test
  public void addClientStreamingMethod() {
    GenericMethodHandlerRegistry registry = new GenericMethodHandlerRegistry();
    ServerServiceDefinition svcDef = registry.addClientStreamingMethod(
        MethodDescriptor.<Void, Void>newBuilder().setType(MethodType.CLIENT_STREAMING)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("foo", "bar"))
            .setRequestMarshaller(TestMethodDescriptors.<Void>noopMarshaller())
            .setResponseMarshaller(TestMethodDescriptors.<Void>noopMarshaller()).build(),
        new ClientStreamingMethod<Void, Void>() {
          @Override
          public StreamObserver<Void> invoke(StreamObserver<Void> responseObserver) {
            return null;
          }
        });

    assertNull(svcDef);
    assertNotNull(registry.lookupMethod("foo/bar"));
  }

  @Test
  public void addBidiStreamingMethod() {
    GenericMethodHandlerRegistry registry = new GenericMethodHandlerRegistry();
    ServerServiceDefinition svcDef = registry.addBidiStreamingMethod(
        MethodDescriptor.<Void, Void>newBuilder().setType(MethodType.BIDI_STREAMING)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("foo", "bar"))
            .setRequestMarshaller(TestMethodDescriptors.<Void>noopMarshaller())
            .setResponseMarshaller(TestMethodDescriptors.<Void>noopMarshaller()).build(),
        new BidiStreamingMethod<Void, Void>() {
          @Override
          public StreamObserver<Void> invoke(StreamObserver<Void> responseObserver) {
            return null;
          }
        });

    assertNull(svcDef);
    assertNotNull(registry.lookupMethod("foo/bar"));
  }
}
