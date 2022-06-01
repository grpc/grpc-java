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

import io.grpc.ClientStreamTracer.StreamInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class GlobalInterceptorsTest {

  @Test
  public void setClientInterceptors() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ClientInterceptor> clientInterceptors = new ArrayList<>();
    clientInterceptors.add(new NoopClientInterceptor());
    clientInterceptors.add(new NoopClientInterceptor());

    instance.setGlobalClientInterceptors(clientInterceptors);
    assertThat(instance.getGlobalClientInterceptors()).hasSize(2);
  }

  @Test
  public void setClientInterceptors_twice() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ClientInterceptor> clientInterceptors = new ArrayList<>();
    clientInterceptors.add(new NoopClientInterceptor());
    clientInterceptors.add(new NoopClientInterceptor());

    instance.setGlobalClientInterceptors(clientInterceptors);
    try {
      instance.setGlobalClientInterceptors(clientInterceptors);
      fail("should have failed for calling setClientInterceptors() again");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("Client interceptors are already set");
    }
  }

  @Test
  public void getBeforeSet_clientInterceptors() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ClientInterceptor> clientInterceptors = instance.getGlobalClientInterceptors();
    assertThat(clientInterceptors).isEmpty();

    try {
      instance.setGlobalClientInterceptors(
          new ArrayList<>(Arrays.asList(new NoopClientInterceptor())));
      fail("should have failed for invoking set call after get is already called");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("Set cannot be called after corresponding Get call");
    }
  }

  @Test
  public void setServerInterceptors() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ServerInterceptor> serverInterceptors = new ArrayList<>();
    serverInterceptors.add(new NoopServerInterceptor());

    instance.setGlobalServerInterceptors(serverInterceptors);
    assertThat(instance.getGlobalServerInterceptors()).hasSize(1);
  }

  @Test
  public void setServerInterceptors_twice() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ServerInterceptor> serverInterceptors = new ArrayList<>();
    serverInterceptors.add(new NoopServerInterceptor());
    serverInterceptors.add(new NoopServerInterceptor());

    instance.setGlobalServerInterceptors(serverInterceptors);
    try {
      instance.setGlobalServerInterceptors(serverInterceptors);
      fail("should have failed for calling setClientInterceptors() again");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("Server interceptors are already set");
    }
  }

  @Test
  public void getBeforeSet_serverInterceptors() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ServerInterceptor> serverInterceptors = instance.getGlobalServerInterceptors();
    assertThat(serverInterceptors).isEmpty();

    try {
      instance.setGlobalServerInterceptors(
          new ArrayList<>(Arrays.asList(new NoopServerInterceptor())));
      fail("should have failed for invoking set call after get is already called");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("Set cannot be called after corresponding Get call");
    }
  }

  @Test
  public void setClientStreamTracerFactories() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ClientStreamTracer.Factory> clientStreamTracerFactories = new ArrayList<>();
    clientStreamTracerFactories.add(DUMMY_CLIENT_TRACER);

    instance.setGlobalClientStreamTracerFactories(clientStreamTracerFactories);
    assertThat(instance.getGlobalClientStreamTracerFactories()).hasSize(1);
  }

  @Test
  public void setClientStreamTracerFactories_twice() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ClientStreamTracer.Factory> clientStreamTracerFactories = new ArrayList<>();
    clientStreamTracerFactories.add(DUMMY_CLIENT_TRACER);
    clientStreamTracerFactories.add(DUMMY_CLIENT_TRACER);

    instance.setGlobalClientStreamTracerFactories(clientStreamTracerFactories);
    try {
      instance.setGlobalClientStreamTracerFactories(clientStreamTracerFactories);
      fail("should have failed for calling setClientInterceptors() again");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("ClientStreamTracer factories are already set");
    }
  }

  @Test
  public void getBeforeSet_clientStreamTracerFactories() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ClientStreamTracer.Factory> clientStreamTracerFactories =
        instance.getGlobalClientStreamTracerFactories();
    assertThat(clientStreamTracerFactories).isEmpty();

    try {
      instance.setGlobalClientStreamTracerFactories(
          new ArrayList<>(Arrays.asList(DUMMY_CLIENT_TRACER)));
      fail("should have failed for invoking set call after get is already called");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("Set cannot be called after corresponding Get call");
    }
  }

  @Test
  public void setServerStreamTracerFactories() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ServerStreamTracer.Factory> serverStreamTracerFactories = new ArrayList<>();
    serverStreamTracerFactories.add(DUMMY_SERVER_TRACER);

    instance.setGlobalServerStreamTracerFactories(serverStreamTracerFactories);
    assertThat(instance.getGlobalServerStreamTracerFactories()).hasSize(1);
  }

  @Test
  public void setServerStreamTracerFactories_twice() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ServerStreamTracer.Factory> serverStreamTracerFactories = new ArrayList<>();
    serverStreamTracerFactories.add(DUMMY_SERVER_TRACER);
    serverStreamTracerFactories.add(DUMMY_SERVER_TRACER);

    instance.setGlobalServerStreamTracerFactories(serverStreamTracerFactories);
    try {
      instance.setGlobalServerStreamTracerFactories(serverStreamTracerFactories);
      fail("should have failed for calling setClientInterceptors() again");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("ServerStreamTracer factories are already set");
    }
  }

  @Test
  public void getBeforeSet_serverStreamTracerFactories() {
    GlobalInterceptors instance = new GlobalInterceptors();
    List<ServerStreamTracer.Factory> serverStreamTracerFactories =
        instance.getGlobalServerStreamTracerFactories();
    assertThat(serverStreamTracerFactories).isEmpty();

    try {
      instance.setGlobalServerStreamTracerFactories(
          new ArrayList<>(Arrays.asList(DUMMY_SERVER_TRACER)));
      fail("should have failed for invoking set call after get is already called");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("Set cannot be called after corresponding Get call");
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

  private static final ClientStreamTracer.Factory DUMMY_CLIENT_TRACER =
      new ClientStreamTracer.Factory() {
        @Override
        public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
          throw new UnsupportedOperationException();
        }
      };

  private static final ServerStreamTracer.Factory DUMMY_SERVER_TRACER =
      new ServerStreamTracer.Factory() {
        @Override
        public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
          throw new UnsupportedOperationException();
        }
      };
}
