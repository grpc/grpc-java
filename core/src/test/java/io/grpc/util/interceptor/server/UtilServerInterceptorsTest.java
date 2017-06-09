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

package io.grpc.util.interceptor.server;

import static com.google.common.collect.Iterables.getOnlyElement;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptors;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.testing.NoopServerCall;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Unit test for {@link io.grpc.ServerInterceptor} implementations that come with gRPC. Not to be
 * confused with the unit tests that validate the core functionality of the server interceptor
 * framework.
 */
@RunWith(JUnit4.class)
public class UtilServerInterceptorsTest {
  @Mock
  private Marshaller<String> requestMarshaller;

  @Mock
  private Marshaller<Integer> responseMarshaller;

  @Mock
  private ServerCallHandler<String, Integer> handler;

  @Mock
  private ServerCall.Listener<String> listener;

  private MethodDescriptor<String, Integer> flowMethod;

  private ServerCall<String, Integer> call = new NoopServerCall<String, Integer>();

  private ServerServiceDefinition serviceDefinition;

  private final Metadata headers = new Metadata();

  /** Set up for test. */
  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    flowMethod = MethodDescriptor.<String, Integer>newBuilder()
        .setType(MethodType.UNKNOWN)
        .setFullMethodName("basic/flow")
        .setRequestMarshaller(requestMarshaller)
        .setResponseMarshaller(responseMarshaller)
        .build();

    Mockito.when(handler.startCall(
        Mockito.<ServerCall<String, Integer>>any(), Mockito.<Metadata>any()))
        .thenReturn(listener);

    serviceDefinition = ServerServiceDefinition.builder(new ServiceDescriptor("basic", flowMethod))
        .addMethod(flowMethod, handler).build();
  }

  @SuppressWarnings("unchecked")
  private static ServerMethodDefinition<String, Integer> getSoleMethod(
      ServerServiceDefinition serviceDef) {
    if (serviceDef.getMethods().size() != 1) {
      throw new AssertionError("Not exactly one method present");
    }
    return (ServerMethodDefinition<String, Integer>) getOnlyElement(serviceDef.getMethods());
  }

  @Test
  public void statusRuntimeExceptionTransmitter() {
    final Status expectedStatus = Status.UNAVAILABLE;
    final Metadata expectedMetadata = new Metadata();
    call = Mockito.spy(call);
    final StatusRuntimeException exception =
        new StatusRuntimeException(expectedStatus, expectedMetadata);
    doThrow(exception).when(listener).onMessage(any(String.class));
    doThrow(exception).when(listener).onCancel();
    doThrow(exception).when(listener).onComplete();
    doThrow(exception).when(listener).onHalfClose();
    doThrow(exception).when(listener).onReady();

    ServerServiceDefinition intercepted = ServerInterceptors.intercept(
        serviceDefinition,
        Arrays.asList(StatusRuntimeExceptionTransmitter.INSTANCE));
    try {
      getSoleMethod(intercepted).getServerCallHandler().startCall(call, headers).onMessage("hello");
      getSoleMethod(intercepted).getServerCallHandler().startCall(call, headers).onCancel();
      getSoleMethod(intercepted).getServerCallHandler().startCall(call, headers).onComplete();
      getSoleMethod(intercepted).getServerCallHandler().startCall(call, headers).onHalfClose();
      getSoleMethod(intercepted).getServerCallHandler().startCall(call, headers).onReady();
    } catch (Throwable t) {
      fail("The interceptor should have handled the error by directly closing the ServerCall, "
          + "and should not propagate it to the method's caller.");
    }
    verify(call, times(5)).close(same(expectedStatus), same(expectedMetadata));
  }
}
