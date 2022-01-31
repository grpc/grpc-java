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

package io.grpc.observability;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ManagedChannelProvider;
import io.grpc.MethodDescriptor;
import io.grpc.TlsChannelCredentials;
import io.grpc.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.testing.TestMethodDescriptors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class LoggingChannelProviderTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private final MethodDescriptor<Void, Void> method = TestMethodDescriptors.voidMethod();

  @Test
  public void initTwiceCausesException() {
    ManagedChannelProvider prevProvider = ManagedChannelProvider.provider();
    assertThat(prevProvider).isNotInstanceOf(LoggingChannelProvider.class);
    LoggingChannelProvider.init(new InternalLoggingChannelInterceptor.FactoryImpl());
    assertThat(ManagedChannelProvider.provider()).isInstanceOf(LoggingChannelProvider.class);
    try {
      LoggingChannelProvider.init(new InternalLoggingChannelInterceptor.FactoryImpl());
      fail("should have failed for calling init() again");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("LoggingChannelProvider already initialized!");
    }
    LoggingChannelProvider.finish();
    assertThat(ManagedChannelProvider.provider()).isSameInstanceAs(prevProvider);
  }

  @Test
  public void forTarget_interceptorCalled() {
    ClientInterceptor interceptor = mock(ClientInterceptor.class,
        delegatesTo(new NoopInterceptor()));
    InternalLoggingChannelInterceptor.Factory factory = mock(
        InternalLoggingChannelInterceptor.Factory.class);
    when(factory.create()).thenReturn(interceptor);
    LoggingChannelProvider.init(factory);
    ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget("localhost");
    ManagedChannel channel = builder.build();
    CallOptions callOptions = CallOptions.DEFAULT;

    ClientCall<Void, Void> unused = channel.newCall(method, callOptions);
    verify(interceptor)
        .interceptCall(same(method), same(callOptions), ArgumentMatchers.<Channel>any());
    channel.shutdownNow();
    LoggingChannelProvider.finish();
  }

  @Test
  public void forAddress_interceptorCalled() {
    ClientInterceptor interceptor = mock(ClientInterceptor.class,
        delegatesTo(new NoopInterceptor()));
    InternalLoggingChannelInterceptor.Factory factory = mock(
        InternalLoggingChannelInterceptor.Factory.class);
    when(factory.create()).thenReturn(interceptor);
    LoggingChannelProvider.init(factory);
    ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress("localhost", 80);
    ManagedChannel channel = builder.build();
    CallOptions callOptions = CallOptions.DEFAULT;

    ClientCall<Void, Void> unused = channel.newCall(method, callOptions);
    verify(interceptor)
        .interceptCall(same(method), same(callOptions), ArgumentMatchers.<Channel>any());
    channel.shutdownNow();
    LoggingChannelProvider.finish();
  }

  @Test
  public void newChannelBuilder_interceptorCalled() {
    ClientInterceptor interceptor = mock(ClientInterceptor.class,
        delegatesTo(new NoopInterceptor()));
    InternalLoggingChannelInterceptor.Factory factory = mock(
        InternalLoggingChannelInterceptor.Factory.class);
    when(factory.create()).thenReturn(interceptor);
    LoggingChannelProvider.init(factory);
    ManagedChannelBuilder<?> builder = Grpc.newChannelBuilder("localhost",
        TlsChannelCredentials.create());
    ManagedChannel channel = builder.build();
    CallOptions callOptions = CallOptions.DEFAULT;

    ClientCall<Void, Void> unused = channel.newCall(method, callOptions);
    verify(interceptor)
        .interceptCall(same(method), same(callOptions), ArgumentMatchers.<Channel>any());
    channel.shutdownNow();
    LoggingChannelProvider.finish();
  }

  private static class NoopInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions, Channel next) {
      return next.newCall(method, callOptions);
    }
  }
}
