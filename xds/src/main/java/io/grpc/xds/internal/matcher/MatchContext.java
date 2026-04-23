/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.xds.internal.matcher;

import com.google.common.base.Preconditions;
import io.grpc.Metadata;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

public final class MatchContext {
  private final Metadata metadata;
  private final Map<String, Object> attributes;

  private MatchContext(Metadata metadata, Map<String, Object> attributes) {
    this.metadata = Preconditions.checkNotNull(metadata, "metadata");
    this.attributes = Collections.unmodifiableMap(new HashMap<>(attributes));
  }

  public Metadata getMetadata() {
    return metadata;
  }

  @Nullable
  public Object getAttribute(String name) {
    AttributeProvider provider = AttributeProviderRegistry.getDefaultRegistry().get(name);
    if (provider != null) {
      return provider.get(this);
    }
    return attributes.get(name);
  }

  @Nullable
  public Object getRawAttribute(String name) {
    return attributes.get(name);
  }

  public Map<String, Object> getRawAttributes() {
    return attributes;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private Metadata metadata = new Metadata();
    private final Map<String, Object> attributes = new HashMap<>();

    public Builder setMetadata(Metadata metadata) {
      this.metadata = Preconditions.checkNotNull(metadata, "metadata");
      return this;
    }

    public Builder setAttribute(String name, Object value) {
      attributes.put(name, value);
      return this;
    }

    public MatchContext build() {
      return new MatchContext(metadata, attributes);
    }
  }
}
