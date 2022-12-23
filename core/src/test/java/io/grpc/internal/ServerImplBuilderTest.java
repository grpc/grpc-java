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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import io.grpc.InternalGlobalInterceptors;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerStreamTracer;
import io.grpc.StaticTestingClassLoader;
import io.grpc.internal.ServerImplBuilder.ClientTransportServersBuilder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ServerImplBuilder}. */
@RunWith(JUnit4.class)
public class ServerImplBuilderTest {
  private static final ServerStreamTracer.Factory DUMMY_USER_TRACER =
      new ServerStreamTracer.Factory() {
        @Override
        public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
          throw new UnsupportedOperationException();
        }
      };
  private static final ServerInterceptor DUMMY_TEST_INTERCEPTOR =
      new ServerInterceptor() {

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
          throw new UnsupportedOperationException();
        }
      };
  private final StaticTestingClassLoader classLoader =
      new StaticTestingClassLoader(
          getClass().getClassLoader(),
          Pattern.compile(
              "io\\.grpc\\.InternalGlobalInterceptors|io\\.grpc\\.GlobalInterceptors|"
                  + "io\\.grpc\\.internal\\.[^.]+"));

  private ServerImplBuilder builder;

  @Before
  public void setUp() throws Exception {
    builder = new ServerImplBuilder(
        new ClientTransportServersBuilder() {
          @Override
          public InternalServer buildClientTransportServers(
              List<? extends ServerStreamTracer.Factory> streamTracerFactories) {
            throw new UnsupportedOperationException();
          }
        });
  }

  @Test
  public void getTracerFactories_default() {
    builder.addStreamTracerFactory(DUMMY_USER_TRACER);

    List<? extends ServerStreamTracer.Factory> factories = builder.getTracerFactories();

    assertEquals(3, factories.size());
    assertThat(factories.get(0).getClass().getName())
        .isEqualTo("io.grpc.census.CensusStatsModule$ServerTracerFactory");
    assertThat(factories.get(1).getClass().getName())
        .isEqualTo("io.grpc.census.CensusTracingModule$ServerTracerFactory");
    assertThat(factories.get(2)).isSameInstanceAs(DUMMY_USER_TRACER);
  }

  @Test
  public void getTracerFactories_disableStats() {
    builder.addStreamTracerFactory(DUMMY_USER_TRACER);
    builder.setStatsEnabled(false);

    List<? extends ServerStreamTracer.Factory> factories = builder.getTracerFactories();

    assertEquals(2, factories.size());
    assertThat(factories.get(0).getClass().getName())
        .isEqualTo("io.grpc.census.CensusTracingModule$ServerTracerFactory");
    assertThat(factories.get(1)).isSameInstanceAs(DUMMY_USER_TRACER);
  }

  @Test
  public void getTracerFactories_disableTracing() {
    builder.addStreamTracerFactory(DUMMY_USER_TRACER);
    builder.setTracingEnabled(false);

    List<? extends ServerStreamTracer.Factory> factories = builder.getTracerFactories();

    assertEquals(2, factories.size());
    assertThat(factories.get(0).getClass().getName())
        .isEqualTo("io.grpc.census.CensusStatsModule$ServerTracerFactory");
    assertThat(factories.get(1)).isSameInstanceAs(DUMMY_USER_TRACER);
  }

  @Test
  public void getTracerFactories_disableBoth() {
    builder.addStreamTracerFactory(DUMMY_USER_TRACER);
    builder.setTracingEnabled(false);
    builder.setStatsEnabled(false);
    List<? extends ServerStreamTracer.Factory> factories = builder.getTracerFactories();
    assertThat(factories).containsExactly(DUMMY_USER_TRACER);
  }

  @Test
  public void getTracerFactories_callsGet() throws Exception {
    Class<?> runnable = classLoader.loadClass(StaticTestingClassLoaderCallsGet.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  public static final class StaticTestingClassLoaderCallsGet implements Runnable {
    @Override
    public void run() {
      ServerImplBuilder builder =
          new ServerImplBuilder(
              streamTracerFactories -> {
                throw new UnsupportedOperationException();
              });
      assertThat(builder.getTracerFactories()).hasSize(2);
      assertThat(builder.interceptors).hasSize(0);
      try {
        InternalGlobalInterceptors.setInterceptorsTracers(
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        fail("exception expected");
      } catch (IllegalStateException e) {
        assertThat(e).hasMessageThat().contains("Set cannot be called after any get call");
      }
    }
  }

  @Test
  public void getTracerFactories_callsSet() throws Exception {
    Class<?> runnable = classLoader.loadClass(StaticTestingClassLoaderCallsSet.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  public static final class StaticTestingClassLoaderCallsSet implements Runnable {
    @Override
    public void run() {
      InternalGlobalInterceptors.setInterceptorsTracers(
          Collections.emptyList(),
          Arrays.asList(DUMMY_TEST_INTERCEPTOR),
          Arrays.asList(DUMMY_USER_TRACER));
      ServerImplBuilder builder =
          new ServerImplBuilder(
              streamTracerFactories -> {
                throw new UnsupportedOperationException();
              });
      assertThat(builder.getTracerFactories()).containsExactly(DUMMY_USER_TRACER);
      assertThat(builder.interceptors).containsExactly(DUMMY_TEST_INTERCEPTOR);
    }
  }

  @Test
  public void getEffectiveInterceptors_setEmpty() throws Exception {
    Class<?> runnable =
        classLoader.loadClass(StaticTestingClassLoaderCallsSetEmpty.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  // UsedReflectively
  public static final class StaticTestingClassLoaderCallsSetEmpty implements Runnable {

    @Override
    public void run() {
      InternalGlobalInterceptors.setInterceptorsTracers(
          Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
      ServerImplBuilder builder =
          new ServerImplBuilder(
              streamTracerFactories -> {
                throw new UnsupportedOperationException();
              });
      assertThat(builder.getTracerFactories()).isEmpty();
      assertThat(builder.interceptors).isEmpty();
    }
  }
}
