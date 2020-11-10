/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Deadline;
import io.grpc.InternalConfigSelector;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.grpc.internal.ManagedChannelServiceConfig.MethodInfo;
import io.grpc.testing.TestMethodDescriptors;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link ConfigSelectingClientCall}. */
@RunWith(JUnit4.class)
public class ConfigSelectingClientCallTest {
  @Rule
  public MockitoRule mockitoRule = MockitoJUnit.rule();
  private final MethodDescriptor<Void, Void> method = MethodDescriptor.<Void, Void>newBuilder()
      .setType(MethodType.UNARY)
      .setFullMethodName("service/method")
      .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
      .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
      .build();
  private final TestChannel channel = new TestChannel();
  @Mock
  private ClientCall.Listener<Void> callListener;
  private TestCall<Void, Void> call;

  @Test
  public void configSelectorInterceptsCall() {
    Map<String, ?> rawMethodConfig = ImmutableMap.of(
        "retryPolicy",
        ImmutableMap.of(
            "maxAttempts", 3.0D,
            "initialBackoff", "1s",
            "maxBackoff", "10s",
            "backoffMultiplier", 1.5D,
            "retryableStatusCodes", ImmutableList.of("UNAVAILABLE")
        ));
    final MethodInfo methodInfo = new MethodInfo(rawMethodConfig, true, 4, 4);
    final Metadata.Key<String> metadataKey =
        Metadata.Key.of("test", Metadata.ASCII_STRING_MARSHALLER);
    final CallOptions.Key<String> callOptionsKey = CallOptions.Key.create("test");
    InternalConfigSelector configSelector = new InternalConfigSelector() {
      @Override
      public Result selectConfig(final PickSubchannelArgs args) {
        ManagedChannelServiceConfig config = new ManagedChannelServiceConfig(
            methodInfo,
            ImmutableMap.<String, MethodInfo>of(),
            ImmutableMap.<String, MethodInfo>of(),
            null,
            null,
            null);
        return Result.newBuilder()
            .setConfig(config)
            .setInterceptor(
                // An interceptor that mutates CallOptions base on headers value.
                new ClientInterceptor() {
                  String value = args.getHeaders().get(metadataKey);
                  @Override
                  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                    callOptions = callOptions.withOption(callOptionsKey, value);
                    return next.newCall(method, callOptions);
                  }
                })
            .build();
      }
    };

    ClientCall<Void, Void> configSelectingClientCall = new ConfigSelectingClientCall<>(
        configSelector,
        channel,
        MoreExecutors.directExecutor(),
        method,
        CallOptions.DEFAULT.withAuthority("bar.authority"));
    Metadata metadata = new Metadata();
    metadata.put(metadataKey, "fooValue");
    configSelectingClientCall.start(callListener, metadata);

    assertThat(call.callOptions.getAuthority()).isEqualTo("bar.authority");
    assertThat(call.callOptions.getOption(MethodInfo.KEY)).isEqualTo(methodInfo);
    assertThat(call.callOptions.getOption(callOptionsKey)).isEqualTo("fooValue");
  }

  @Test
  public void configDeadlinePropagatedToCall() {
    CallOptions callOptions = CallOptions.DEFAULT.withDeadline(Deadline.after(2000, SECONDS));
    final ClientInterceptor noopInterceptor = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return next.newCall(method, callOptions);
      }
    };

    // Case: config Deadline expires later than CallOptions Deadline
    InternalConfigSelector configSelector = new InternalConfigSelector() {
      @Override
      public Result selectConfig(PickSubchannelArgs args) {
        Map<String, ?> rawMethodConfig = ImmutableMap.of(
            "timeout",
            "3000s");
        MethodInfo methodInfo = new MethodInfo(rawMethodConfig, false, 0, 0);
        ManagedChannelServiceConfig config = new ManagedChannelServiceConfig(
            methodInfo,
            ImmutableMap.<String, MethodInfo>of(),
            ImmutableMap.<String, MethodInfo>of(),
            null,
            null,
            null);
        return Result.newBuilder()
            .setConfig(config)
            .setInterceptor(noopInterceptor)
            .build();
      }
    };

    ClientCall<Void, Void> configSelectingClientCall = new ConfigSelectingClientCall<>(
        configSelector,
        channel,
        MoreExecutors.directExecutor(),
        method,
        callOptions);
    configSelectingClientCall.start(callListener, new Metadata());
    assertThat(call.callOptions.getDeadline()).isLessThan(Deadline.after(2001, SECONDS));

    // Case: config Deadline expires earlier than CallOptions Deadline
    configSelector = new InternalConfigSelector() {
      @Override
      public Result selectConfig(PickSubchannelArgs args) {
        Map<String, ?> rawMethodConfig = ImmutableMap.of(
            "timeout",
            "1000s");
        MethodInfo methodInfo = new MethodInfo(rawMethodConfig, false, 0, 0);
        ManagedChannelServiceConfig config = new ManagedChannelServiceConfig(
            methodInfo,
            ImmutableMap.<String, MethodInfo>of(),
            ImmutableMap.<String, MethodInfo>of(),
            null,
            null,
            null);
        return Result.newBuilder()
            .setConfig(config)
            .setInterceptor(noopInterceptor)
            .build();
      }
    };
    configSelectingClientCall = new ConfigSelectingClientCall<>(
        configSelector,
        channel,
        MoreExecutors.directExecutor(),
        method,
        callOptions);
    configSelectingClientCall.start(callListener, new Metadata());
    assertThat(call.callOptions.getDeadline()).isLessThan(Deadline.after(1001, MINUTES));
  }

  @Test
  public void selectionErrorPropagatedToListener() {
    InternalConfigSelector configSelector = new InternalConfigSelector() {
      @Override
      public Result selectConfig(PickSubchannelArgs args) {
        return Result.forError(Status.FAILED_PRECONDITION);
      }
    };

    ClientCall<Void, Void> configSelectingClientCall = new ConfigSelectingClientCall<>(
        configSelector,
        channel,
        MoreExecutors.directExecutor(),
        method,
        CallOptions.DEFAULT);
    configSelectingClientCall.start(callListener, new Metadata());
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(callListener).onClose(statusCaptor.capture(), any(Metadata.class));
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  private final class TestChannel extends Channel {

    @SuppressWarnings("unchecked") // Don't care
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
      TestCall<ReqT, RespT> clientCall = new TestCall<>(callOptions);
      call = (TestCall<Void, Void>) clientCall;
      return clientCall;
    }

    @Override
    public String authority() {
      return "foo.authority";
    }
  }

  private static final class TestCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {
    final CallOptions callOptions;

    TestCall(CallOptions callOptions) {
      this.callOptions = callOptions;
    }

    @Override
    public void start(Listener<RespT> responseListener, Metadata headers) {}

    @Override
    public void request(int numMessages) {}

    @Override
    public void cancel(@Nullable String message, @Nullable Throwable cause) {}

    @Override
    public void halfClose() {}

    @Override
    public void sendMessage(ReqT message) {}
  }
}
