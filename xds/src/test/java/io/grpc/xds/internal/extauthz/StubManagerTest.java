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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.Any;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.envoyproxy.envoy.extensions.grpc_service.call_credentials.access_token.v3.AccessTokenCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.grpc.ManagedChannel;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfigChannelFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class StubManagerTest {

  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock
  private GrpcServiceConfigChannelFactory channelFactory;
  @Mock
  private ManagedChannel channel1;
  @Mock
  private ManagedChannel channel2;

  private StubManager stubManager;
  private ExtAuthzConfig config1;
  private ExtAuthzConfig config2;

  @Before
  public void setUp() throws ExtAuthzParseException {
    stubManager = StubManager.create(channelFactory);
    config1 = buildExtAuthzConfig("target1");
    config2 = buildExtAuthzConfig("target2");

    when(channelFactory.createChannel(config1.grpcService())).thenReturn(channel1);
    when(channelFactory.createChannel(config2.grpcService())).thenReturn(channel2);
  }

  private ExtAuthzConfig buildExtAuthzConfig(String targetUri) throws ExtAuthzParseException {
    ExtAuthz extAuthz = ExtAuthz.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder().setTargetUri(targetUri)
                .addChannelCredentialsPlugin(Any.pack(InsecureCredentials.newBuilder().build()))
                .addCallCredentialsPlugin(
                    Any.pack(AccessTokenCredentials.newBuilder().setToken("fake-token").build()))
                .build())
            .build())
        .build();
    return ExtAuthzConfig.fromProto(extAuthz);
  }

  @Test
  public void getStub_createsNewStubAndChannel_firstTime() {
    AuthorizationGrpc.AuthorizationStub stub = stubManager.getStub(config1);
    assertThat(stub).isNotNull();
    verify(channelFactory).createChannel(config1.grpcService());
  }

  @Test
  public void getStub_returnsExistingStub_sameConfig() throws ExtAuthzParseException {
    AuthorizationGrpc.AuthorizationStub stub1 = stubManager.getStub(config1);
    ExtAuthzConfig sameAsConfig1 = buildExtAuthzConfig("target1");
    AuthorizationGrpc.AuthorizationStub stub2 = stubManager.getStub(sameAsConfig1);

    assertThat(stub1).isSameInstanceAs(stub2);
    verify(channelFactory, times(1)).createChannel(config1.grpcService());
  }

  @Test
  public void getStub_createsNewStubAndShutsDownOld_differentConfig() {
    AuthorizationGrpc.AuthorizationStub stub1 = stubManager.getStub(config1);
    AuthorizationGrpc.AuthorizationStub stub2 = stubManager.getStub(config2);

    assertThat(stub1).isNotSameInstanceAs(stub2);
    verify(channelFactory).createChannel(config1.grpcService());
    verify(channelFactory).createChannel(config2.grpcService());
    verify(channel1).shutdown();
    verify(channel2, never()).shutdown();
  }

  @Test
  public void getStub_createsNewStubAndShutsDownOld_differentTarget()
      throws ExtAuthzParseException {
    config2 = buildExtAuthzConfig("target1-different");
    when(channelFactory.createChannel(config2.grpcService())).thenReturn(channel2);

    AuthorizationGrpc.AuthorizationStub stub1 = stubManager.getStub(config1);
    AuthorizationGrpc.AuthorizationStub stub2 = stubManager.getStub(config2);

    assertThat(stub1).isNotSameInstanceAs(stub2);
    verify(channelFactory).createChannel(config1.grpcService());
    verify(channelFactory).createChannel(config2.grpcService());
    verify(channel1).shutdown();
  }

  @Test
  public void getStub_createsNewStubAndShutsDownOld_differentCredentialsHash()
      throws ExtAuthzParseException {
    when(channelFactory.createChannel(config2.grpcService())).thenReturn(channel2);

    AuthorizationGrpc.AuthorizationStub stub1 = stubManager.getStub(config1);
    AuthorizationGrpc.AuthorizationStub stub2 = stubManager.getStub(config2);

    assertThat(stub1).isNotSameInstanceAs(stub2);
    verify(channelFactory).createChannel(config1.grpcService());
    verify(channelFactory).createChannel(config2.grpcService());
    verify(channel1).shutdown();
  }

  @Test
  public void close_shutsDownChannel() {
    stubManager.getStub(config1);
    stubManager.close();
    verify(channel1).shutdown();
  }

  @Test
  public void close_noStubCreated_doesNothing() {
    stubManager.close();
    verify(channel1, never()).shutdown();
  }
}
