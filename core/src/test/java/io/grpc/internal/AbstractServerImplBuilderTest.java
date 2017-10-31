/*
 * Copyright 2017, gRPC Authors All rights reserved.
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
import io.opencensus.common.Scope;
import io.opencensus.stats.StatsRecord;
import io.opencensus.stats.StatsRecorder;
import io.opencensus.tags.TagContext;
import io.opencensus.tags.TagContextBuilder;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.propagation.TagContextBinarySerializer;
import io.opencensus.tags.propagation.TagContextParseException;
import java.io.File;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AbstractServerImplBuilder}. */
@RunWith(JUnit4.class)
public class AbstractServerImplBuilderTest {

  private static final Tagger DUMMY_TAGGER =
      new Tagger() {
        @Override
        public TagContext empty() {
          throw new UnsupportedOperationException();
        }

        @Override
        public TagContext getCurrentTagContext() {
          throw new UnsupportedOperationException();
        }

        @Override
        public TagContextBuilder emptyBuilder() {
          throw new UnsupportedOperationException();
        }

        @Override
        public TagContextBuilder toBuilder(TagContext tags) {
          throw new UnsupportedOperationException();
        }

        @Override
        public TagContextBuilder currentBuilder() {
          throw new UnsupportedOperationException();
        }

        @Override
        public Scope withTagContext(TagContext tags) {
          throw new UnsupportedOperationException();
        }
      };

  private static final TagContextBinarySerializer DUMMY_TAG_CONTEXT_BINARY_SERIALIZER =
      new TagContextBinarySerializer() {
        @Override
        public byte[] toByteArray(TagContext tags) {
          throw new UnsupportedOperationException();
        }

        @Override
        public TagContext fromByteArray(byte[] bytes) throws TagContextParseException {
          throw new UnsupportedOperationException();
        }
      };

  private static final StatsRecorder DUMMY_STATS_RECORDER =
      new StatsRecorder() {
        @Override
        public StatsRecord newRecord() {
          throw new UnsupportedOperationException();
        }
      };

  private static final ServerStreamTracer.Factory DUMMY_USER_TRACER =
      new ServerStreamTracer.Factory() {
        @Override
        public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
          throw new UnsupportedOperationException();
        }
      };

  private Builder builder = new Builder();

  @Test
  public void getTracerFactories_default() {
    builder.addStreamTracerFactory(DUMMY_USER_TRACER);
    List<ServerStreamTracer.Factory> factories = builder.getTracerFactories();
    assertEquals(3, factories.size());
    assertThat(factories.get(0)).isInstanceOf(CensusStatsModule.ServerTracerFactory.class);
    assertThat(factories.get(1)).isInstanceOf(CensusTracingModule.ServerTracerFactory.class);
    assertThat(factories.get(2)).isSameAs(DUMMY_USER_TRACER);
  }

  @Test
  public void getTracerFactories_disableStats() {
    builder.addStreamTracerFactory(DUMMY_USER_TRACER);
    builder.setStatsEnabled(false);
    List<ServerStreamTracer.Factory> factories = builder.getTracerFactories();
    assertEquals(2, factories.size());
    assertThat(factories.get(0)).isInstanceOf(CensusTracingModule.ServerTracerFactory.class);
    assertThat(factories.get(1)).isSameAs(DUMMY_USER_TRACER);
  }

  @Test
  public void getTracerFactories_disableTracing() {
    builder.addStreamTracerFactory(DUMMY_USER_TRACER);
    builder.setTracingEnabled(false);
    List<ServerStreamTracer.Factory> factories = builder.getTracerFactories();
    assertEquals(2, factories.size());
    assertThat(factories.get(0)).isInstanceOf(CensusStatsModule.ServerTracerFactory.class);
    assertThat(factories.get(1)).isSameAs(DUMMY_USER_TRACER);
  }

  @Test
  public void getTracerFactories_disableBoth() {
    builder.addStreamTracerFactory(DUMMY_USER_TRACER);
    builder.setTracingEnabled(false);
    builder.setStatsEnabled(false);
    List<ServerStreamTracer.Factory> factories = builder.getTracerFactories();
    assertThat(factories).containsExactly(DUMMY_USER_TRACER);
  }

  static class Builder extends AbstractServerImplBuilder<Builder> {
    Builder() {
      statsImplementation(DUMMY_TAGGER, DUMMY_TAG_CONTEXT_BINARY_SERIALIZER, DUMMY_STATS_RECORDER);
    }

    @Override
    protected io.grpc.internal.InternalServer buildTransportServer(
        List<ServerStreamTracer.Factory> streamTracerFactories) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Builder useTransportSecurity(File certChain, File privateKey) {
      throw new UnsupportedOperationException();
    }
  }

}
