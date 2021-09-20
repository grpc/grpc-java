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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.InternalConfigSelector;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.ManagedChannelImpl.ConfigSelectingClientCall;
import io.grpc.internal.ManagedChannelServiceConfig.MethodInfo;
import io.grpc.testing.TestMethodDescriptors;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link ManagedChannelImpl.ConfigSelectingClientCall}. */
@RunWith(JUnit4.class)
public class ConfigSelectingClientCallTest {
  @Rule
  public MockitoRule mockitoRule = MockitoJUnit.rule();
  private final MethodDescriptor<Void, Void> method = TestMethodDescriptors.voidMethod();

  private TestChannel channel = new TestChannel();
  // The underlying call directly created by the channel.
  private TestCall<?, ?> call;

  @Mock
  private ClientCall.Listener<Void> callListener;

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
                // An interceptor that mutates CallOptions based on headers value.
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

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
      TestCall<ReqT, RespT> clientCall = new TestCall<>(callOptions);
      call = clientCall;
      return clientCall;
    }

    @Override
    public String authority() {
      return "foo.authority";
    }
  }

  private static final class TestCall<ReqT, RespT> extends NoopClientCall<ReqT, RespT> {
    // CallOptions actually received from the channel when the call is created.
    final CallOptions callOptions;

    TestCall(CallOptions callOptions) {
      this.callOptions = callOptions;
    }
  }
}
