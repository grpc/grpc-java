/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.extauthz;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.RuntimeFeatureFlag;
import io.envoyproxy.envoy.config.core.v3.RuntimeFractionalPercent;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.envoyproxy.envoy.extensions.grpc_service.call_credentials.access_token.v3.AccessTokenCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.google_default.v3.GoogleDefaultCredentials;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import io.grpc.internal.FailingClientCall;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.internal.ThreadSafeRandom;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class ExtAuthzClientInterceptorTest {

  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock
  private CheckResponseHandler responseHandler;
  @Mock
  private HeaderMutator headerMutator;

  @Mock
  private CheckRequestBuilder checkRequestBuilder;

  @Mock
  ClientCall<Void, Void> expectedCall;

  @Mock
  ClientCall<Void, Void> nextCall;

  private AuthorizationGrpc.AuthorizationStub authzStub;

  @Mock
  private ThreadSafeRandom random;

  @Mock
  private BufferingAuthzClientCall.Factory clientCallFactory;

  @Mock
  private Channel next;

  private MethodDescriptor<Void, Void> method = TestMethodDescriptors.voidMethod();
  private CallOptions callOptions = CallOptions.DEFAULT;

  @Before
  public void setUp() {
    authzStub = AuthorizationGrpc.newStub(mock(Channel.class));
  }

  @Test
  public void interceptCall_denyAtDisable() throws ExtAuthzParseException {
    when(random.nextInt(100)).thenReturn(50);
    ExtAuthz extAuthzProto = ExtAuthz.newBuilder().setGrpcService(GrpcService.newBuilder()
        .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder().setTargetUri("test-cluster")
            .addChannelCredentialsPlugin(Any.pack(GoogleDefaultCredentials.newBuilder().build()))
            .addCallCredentialsPlugin(
                Any.pack(AccessTokenCredentials.newBuilder().setToken("fake-token").build()))
            .build())
        .build())
        .setFilterEnabled(RuntimeFractionalPercent.newBuilder()
            .setDefaultValue(FractionalPercent.newBuilder().setNumerator(100)
                .setDenominator(FractionalPercent.DenominatorType.HUNDRED).build())
            .build())
        .setDenyAtDisable(
            RuntimeFeatureFlag.newBuilder().setDefaultValue(BoolValue.of(true)).build())
        .setStatusOnError(
            io.envoyproxy.envoy.type.v3.HttpStatus.newBuilder().setCodeValue(403).build())
        .build();
    ExtAuthzConfig config = ExtAuthzConfig.fromProto(extAuthzProto);
    ClientInterceptor interceptor = ExtAuthzClientInterceptor.INSTANCE.create(config, authzStub,
        random, clientCallFactory, checkRequestBuilder, responseHandler, headerMutator);

    ClientCall<Void, Void> call = interceptor.interceptCall(method, callOptions, next);

    assertThat(call).isInstanceOf(FailingClientCall.class);
  }

  @Test
  public void interceptCall_delegateToRealCall() throws ExtAuthzParseException {
    when(random.nextInt(100)).thenReturn(50);
    ExtAuthz extAuthzProto = ExtAuthz.newBuilder()
        .setGrpcService(io.envoyproxy.envoy.config.core.v3.GrpcService.newBuilder()
            .setGoogleGrpc(io.envoyproxy.envoy.config.core.v3.GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("test-cluster")
                .addChannelCredentialsPlugin(
                    Any.pack(GoogleDefaultCredentials.newBuilder().build()))
                .addCallCredentialsPlugin(
                    Any.pack(AccessTokenCredentials.newBuilder().setToken("fake-token").build()))
                .build()))
        .setFilterEnabled(RuntimeFractionalPercent.newBuilder()
            .setDefaultValue(FractionalPercent.newBuilder().setNumerator(100)
                .setDenominator(FractionalPercent.DenominatorType.HUNDRED).build())
            .build())
        .build();
    ExtAuthzConfig config = ExtAuthzConfig.fromProto(extAuthzProto);
    ClientInterceptor interceptor = ExtAuthzClientInterceptor.INSTANCE.create(config, authzStub,
        random, clientCallFactory, checkRequestBuilder, responseHandler, headerMutator);
    when(next.newCall(method, callOptions)).thenReturn(expectedCall);

    ClientCall<Void, Void> call = interceptor.interceptCall(method, callOptions, next);

    assertThat(call).isSameInstanceAs(expectedCall);
  }

  @Test
  public void interceptCall_factoryCreatesCall() throws ExtAuthzParseException {
    ExtAuthz extAuthzProto = ExtAuthz.newBuilder()
        .setGrpcService(io.envoyproxy.envoy.config.core.v3.GrpcService.newBuilder()
            .setGoogleGrpc(io.envoyproxy.envoy.config.core.v3.GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("test-cluster")
                .addChannelCredentialsPlugin(
                    Any.pack(GoogleDefaultCredentials.newBuilder().build()))
                .addCallCredentialsPlugin(
                    Any.pack(AccessTokenCredentials.newBuilder().setToken("fake-token").build()))
                .build())
            .build())
        .setFilterEnabled(RuntimeFractionalPercent.newBuilder()
            .setDefaultValue(FractionalPercent.newBuilder().setNumerator(0)
                .setDenominator(FractionalPercent.DenominatorType.HUNDRED).build())
            .build())
        .build();
    ExtAuthzConfig config = ExtAuthzConfig.fromProto(extAuthzProto);
    when(random.nextInt(100)).thenReturn(50);
    ClientInterceptor interceptor = ExtAuthzClientInterceptor.INSTANCE.create(config, authzStub,
        random, clientCallFactory, checkRequestBuilder, responseHandler, headerMutator);
    when(next.newCall(method, callOptions)).thenReturn(nextCall);
    when(clientCallFactory.create(eq(nextCall), eq(config), eq(authzStub), eq(checkRequestBuilder),
        eq(responseHandler), eq(headerMutator), eq(method), any(CallBuffer.class)))
            .thenReturn(expectedCall);
    ClientCall<Void, Void> call = interceptor.interceptCall(method, callOptions, next);
    assertThat(call).isSameInstanceAs(expectedCall);
  }
}
