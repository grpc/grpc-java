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
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GlobalInterceptorsTest {

  private final StaticTestingClassLoader classLoader =
      new StaticTestingClassLoader(
          getClass().getClassLoader(), Pattern.compile("io\\.grpc\\.[^.]+"));

  @Test
  public void setInterceptorsTracers() throws Exception {
    Class<?> runnable = classLoader.loadClass(StaticTestingClassLoaderSet.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  @Test
  public void setGlobalInterceptorsTracers_twice() throws Exception {
    Class<?> runnable = classLoader.loadClass(StaticTestingClassLoaderSetTwice.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  @Test
  public void getBeforeSet_clientInterceptors() throws Exception {
    Class<?> runnable =
        classLoader.loadClass(
            StaticTestingClassLoaderGetBeforeSetClientInterceptor.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  @Test
  public void getBeforeSet_serverInterceptors() throws Exception {
    Class<?> runnable =
        classLoader.loadClass(
            StaticTestingClassLoaderGetBeforeSetServerInterceptor.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  @Test
  public void getBeforeSet_serverStreamTracerFactories() throws Exception {
    Class<?> runnable =
        classLoader.loadClass(
            StaticTestingClassLoaderGetBeforeSetServerStreamTracerFactory.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  // UsedReflectively
  public static final class StaticTestingClassLoaderSet implements Runnable {
    @Override
    public void run() {
      List<ClientInterceptor> clientInterceptorList =
          new ArrayList<>(Arrays.asList(new NoopClientInterceptor()));
      List<ServerInterceptor> serverInterceptorList =
          new ArrayList<>(Arrays.asList(new NoopServerInterceptor()));
      List<ServerStreamTracer.Factory> serverStreamTracerFactoryList =
          new ArrayList<>(
              Arrays.asList(
                  new NoopServerStreamTracerFactory(), new NoopServerStreamTracerFactory()));

      GlobalInterceptors.setInterceptorsTracers(
          clientInterceptorList, serverInterceptorList, serverStreamTracerFactoryList);

      assertThat(GlobalInterceptors.getClientInterceptors()).isEqualTo(clientInterceptorList);
      assertThat(GlobalInterceptors.getServerInterceptors()).isEqualTo(serverInterceptorList);
      assertThat(GlobalInterceptors.getServerStreamTracerFactories())
          .isEqualTo(serverStreamTracerFactoryList);
    }
  }

  public static final class StaticTestingClassLoaderSetTwice implements Runnable {
    @Override
    public void run() {
      GlobalInterceptors.setInterceptorsTracers(
          new ArrayList<>(Arrays.asList(new NoopClientInterceptor())),
              Collections.emptyList(),
          new ArrayList<>(Arrays.asList(new NoopServerStreamTracerFactory())));
      try {
        GlobalInterceptors.setInterceptorsTracers(
            null, new ArrayList<>(Arrays.asList(new NoopServerInterceptor())), null);
        fail("should have failed for calling setGlobalInterceptorsTracers() again");
      } catch (IllegalStateException e) {
        assertThat(e).hasMessageThat().isEqualTo("Global interceptors and tracers are already set");
      }
    }
  }

  public static final class StaticTestingClassLoaderGetBeforeSetClientInterceptor
      implements Runnable {
    @Override
    public void run() {
      List<ClientInterceptor> clientInterceptors = GlobalInterceptors.getClientInterceptors();
      assertThat(clientInterceptors).isNull();

      try {
        GlobalInterceptors.setInterceptorsTracers(
            new ArrayList<>(Arrays.asList(new NoopClientInterceptor())), null, null);
        fail("should have failed for invoking set call after get is already called");
      } catch (IllegalStateException e) {
        assertThat(e).hasMessageThat().isEqualTo("Set cannot be called after any get call");
      }
    }
  }

  public static final class StaticTestingClassLoaderGetBeforeSetServerInterceptor
      implements Runnable {
    @Override
    public void run() {
      List<ServerInterceptor> serverInterceptors = GlobalInterceptors.getServerInterceptors();
      assertThat(serverInterceptors).isNull();

      try {
        GlobalInterceptors.setInterceptorsTracers(
            null, new ArrayList<>(Arrays.asList(new NoopServerInterceptor())), null);
        fail("should have failed for invoking set call after get is already called");
      } catch (IllegalStateException e) {
        assertThat(e).hasMessageThat().isEqualTo("Set cannot be called after any get call");
      }
    }
  }

  public static final class StaticTestingClassLoaderGetBeforeSetServerStreamTracerFactory
      implements Runnable {
    @Override
    public void run() {
      List<ServerStreamTracer.Factory> serverStreamTracerFactories =
          GlobalInterceptors.getServerStreamTracerFactories();
      assertThat(serverStreamTracerFactories).isNull();

      try {
        GlobalInterceptors.setInterceptorsTracers(
            null, null, new ArrayList<>(Arrays.asList(new NoopServerStreamTracerFactory())));
        fail("should have failed for invoking set call after get is already called");
      } catch (IllegalStateException e) {
        assertThat(e).hasMessageThat().isEqualTo("Set cannot be called after any get call");
      }
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

  private static class NoopServerStreamTracerFactory extends ServerStreamTracer.Factory {
    @Override
    public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
      throw new UnsupportedOperationException();
    }
  }
}
