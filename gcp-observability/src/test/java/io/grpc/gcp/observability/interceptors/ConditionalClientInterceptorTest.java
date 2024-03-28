/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.gcp.observability.interceptors;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import java.util.function.BiPredicate;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link ConditionalClientInterceptor}.
 */
@RunWith(JUnit4.class)
public class ConditionalClientInterceptorTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private ConditionalClientInterceptor conditionalClientInterceptor;
  @Mock private ClientInterceptor delegate;
  @Mock private BiPredicate<MethodDescriptor<?, ?>, CallOptions> predicate;
  @Mock private Channel channel;
  @Mock private ClientCall<?, ?> returnedCall;
  private MethodDescriptor<?, ?> method;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() throws Exception {
    conditionalClientInterceptor = new ConditionalClientInterceptor(
        delegate, predicate);
    method = MethodDescriptor.newBuilder().setType(MethodType.UNARY)
        .setFullMethodName("service/method")
        .setRequestMarshaller(mock(MethodDescriptor.Marshaller.class))
        .setResponseMarshaller(mock(MethodDescriptor.Marshaller.class))
        .build();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void predicateFalse() {
    when(predicate.test(any(MethodDescriptor.class), any(CallOptions.class))).thenReturn(false);
    doReturn(returnedCall).when(channel).newCall(method, CallOptions.DEFAULT);
    ClientCall<?, ?> clientCall = conditionalClientInterceptor.interceptCall(method,
        CallOptions.DEFAULT, channel);
    assertThat(clientCall).isSameInstanceAs(returnedCall);
    verify(delegate, never()).interceptCall(any(MethodDescriptor.class), any(CallOptions.class),
        any(Channel.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void predicateTrue() {
    when(predicate.test(any(MethodDescriptor.class), any(CallOptions.class))).thenReturn(true);
    doReturn(returnedCall).when(delegate).interceptCall(method, CallOptions.DEFAULT, channel);
    ClientCall<?, ?> clientCall = conditionalClientInterceptor.interceptCall(method,
        CallOptions.DEFAULT, channel);
    assertThat(clientCall).isSameInstanceAs(returnedCall);
    verify(channel, never()).newCall(any(MethodDescriptor.class), any(CallOptions.class));
  }
}
