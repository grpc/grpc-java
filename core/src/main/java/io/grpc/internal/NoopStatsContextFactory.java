/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.internal;

import com.google.instrumentation.stats.MeasurementMap;
import com.google.instrumentation.stats.StatsContext;
import com.google.instrumentation.stats.StatsContextFactory;
import com.google.instrumentation.stats.TagKey;
import com.google.instrumentation.stats.TagValue;
import java.io.InputStream;
import java.io.OutputStream;

public final class NoopStatsContextFactory extends StatsContextFactory {
  private static final StatsContext DEFAULT_CONTEXT = new NoopStatsContext();
  private static final StatsContext.Builder BUILDER = new NoopContextBuilder();

  public static final StatsContextFactory INSTANCE = new NoopStatsContextFactory();

  private NoopStatsContextFactory() {
  }

  @Override
  public StatsContext deserialize(InputStream is) {
    return DEFAULT_CONTEXT;
  }

  @Override
  public StatsContext getDefault() {
    return DEFAULT_CONTEXT;
  }

  private static class NoopStatsContext extends StatsContext {
    @Override
    public Builder builder() {
      return BUILDER;
    }

    @Override
    public StatsContext record(MeasurementMap metrics) {
      return DEFAULT_CONTEXT;
    }

    @Override
    public void serialize(OutputStream os) {
      return;
    }
  }

  private static class NoopContextBuilder extends StatsContext.Builder {
    @Override
    public StatsContext.Builder set(TagKey key, TagValue value) {
      return this;
    }

    @Override
    public StatsContext build() {
      return DEFAULT_CONTEXT;
    }
  }
}
