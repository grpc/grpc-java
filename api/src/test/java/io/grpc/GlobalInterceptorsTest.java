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

package io.grpc;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class GlobalInterceptorsTest {

  private static final ServerStreamTracer.Factory DUMMY_SERVER_TRACER =
      new ServerStreamTracer.Factory() {
        @Override
        public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
          throw new UnsupportedOperationException();
        }
      };

  @Test
  public void setClientInterceptors() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ClientInterceptor> clientInterceptors = new ArrayList<>();
    clientInterceptors.add(new NoopClientInterceptor());
    clientInterceptors.add(new NoopClientInterceptor());

    instance.setGlobalInterceptorsTracers(clientInterceptors, null, null);
    assertThat(instance.getGlobalClientInterceptors()).hasSize(2);
  }

  @Test
  public void setServerInterceptors() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ServerInterceptor> serverInterceptors = new ArrayList<>();
    serverInterceptors.add(new NoopServerInterceptor());

    instance.setGlobalInterceptorsTracers(null, serverInterceptors, null);
    assertThat(instance.getGlobalServerInterceptors()).hasSize(1);
  }

  @Test
  public void setServerStreamTracerFactories() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ServerStreamTracer.Factory> serverStreamTracerFactories = new ArrayList<>();
    serverStreamTracerFactories.add(DUMMY_SERVER_TRACER);

    instance.setGlobalInterceptorsTracers(null, null, serverStreamTracerFactories);
    assertThat(instance.getGlobalServerStreamTracerFactories()).hasSize(1);
  }

  @Test
  public void setInterceptorsTracers() {
    GlobalInterceptors instance = new GlobalInterceptors();
    instance.setGlobalInterceptorsTracers(
        new ArrayList<>(Arrays.asList(new NoopClientInterceptor())),
        new ArrayList<>(Arrays.asList(new NoopServerInterceptor())),
        new ArrayList<>(Arrays.asList(DUMMY_SERVER_TRACER, DUMMY_SERVER_TRACER)));
    assertThat(instance.getGlobalClientInterceptors()).hasSize(1);
    assertThat(instance.getGlobalServerInterceptors()).hasSize(1);
    assertThat(instance.getGlobalServerStreamTracerFactories()).hasSize(2);
  }

  @Test
  public void setGlobalInterceptorsTracers_twice() {
    GlobalInterceptors instance = new GlobalInterceptors();
    instance.setGlobalInterceptorsTracers(
        new ArrayList<>(Arrays.asList(new NoopClientInterceptor())),
        null,
        new ArrayList<>(Arrays.asList(DUMMY_SERVER_TRACER)));
    try {
      instance.setGlobalInterceptorsTracers(
          null, new ArrayList<>(Arrays.asList(new NoopServerInterceptor())), null);
      fail("should have failed for calling setGlobalInterceptorsTracers() again");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("Global interceptors and tracers are already set");
    }
  }

  @Test
  public void getBeforeSet_clientInterceptors() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ClientInterceptor> clientInterceptors = instance.getGlobalClientInterceptors();
    assertThat(clientInterceptors).isNull();

    try {
      instance.setGlobalInterceptorsTracers(
          new ArrayList<>(Arrays.asList(new NoopClientInterceptor())), null, null);
      fail("should have failed for invoking set call after get is already called");
    } catch (IllegalStateException e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo("Set cannot be called after any corresponding get call");
    }
  }

  @Test
  public void getBeforeSet_serverInterceptors() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ServerInterceptor> serverInterceptors = instance.getGlobalServerInterceptors();
    assertThat(serverInterceptors).isNull();

    try {
      instance.setGlobalInterceptorsTracers(
          null, new ArrayList<>(Arrays.asList(new NoopServerInterceptor())), null);
      fail("should have failed for invoking set call after get is already called");
    } catch (IllegalStateException e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo("Set cannot be called after any corresponding get call");
    }
  }

  @Test
  public void getBeforeSet_serverStreamTracerFactories() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ServerStreamTracer.Factory> serverStreamTracerFactories =
        instance.getGlobalServerStreamTracerFactories();
    assertThat(serverStreamTracerFactories).isNull();

    try {
      instance.setGlobalInterceptorsTracers(
          null, null, new ArrayList<>(Arrays.asList(DUMMY_SERVER_TRACER)));
      fail("should have failed for invoking set call after get is already called");
    } catch (IllegalStateException e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo("Set cannot be called after any corresponding get call");
    }
  }

  private static class NoopClientInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      return next.newCall(method, callOptions);
    }
  }

  private static class NoopServerInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      return next.startCall(call, headers);
    }
  }
}
