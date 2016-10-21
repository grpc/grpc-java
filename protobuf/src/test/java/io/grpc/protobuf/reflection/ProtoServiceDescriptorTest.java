/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.protobuf.reflection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.grpc.MethodDescriptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collection;
import java.util.Collections;

/** Unit tests for {@link ProtoServiceDescriptor}. */
@RunWith(JUnit4.class)
public class ProtoServiceDescriptorTest {
  @Test
  public void compatibilityWithServerInterceptorsTest() {
    ProtoServiceDescriptor protoServiceDescriptor =
        new DummyProtoServiceDescriptor(
            "service name", Collections.<MethodDescriptor<?, ?>>emptyList());
    ServerServiceDefinition serviceDefinition =
        ServerServiceDefinition.builder(protoServiceDescriptor).build();
    ServerServiceDefinition wrappedServiceDefinition =
        ServerInterceptors.useInputStreamMessages(serviceDefinition);
    assertEquals(
        serviceDefinition.getServiceDescriptor().getName(),
        wrappedServiceDefinition.getServiceDescriptor().getName());
    assertTrue(serviceDefinition.getServiceDescriptor() instanceof ProtoServiceDescriptor);
  }

  private static class DummyProtoServiceDescriptor extends ProtoServiceDescriptor {
    public DummyProtoServiceDescriptor(String name, MethodDescriptor<?, ?>... methods) {
      super(name, methods);
    }

    public DummyProtoServiceDescriptor(String name, Collection<MethodDescriptor<?, ?>> methods) {
      super(name, methods);
    }

    @Override
    protected DummyProtoServiceDescriptor withMethods(Collection<MethodDescriptor<?, ?>> methods) {
      return new DummyProtoServiceDescriptor(getName(), methods);
    }

    @Override
    public com.google.protobuf.Descriptors.FileDescriptor getFile() {
      return null;
    }
  }
}
