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

import io.grpc.Metadata;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.ServerImplBuilder.ClientTransportServersBuilder;
import java.util.List;
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
}
