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

import com.github.xds.core.v3.TypedExtensionConfig;
import com.github.xds.type.matcher.v3.Matcher;
import com.google.common.base.Preconditions;
import io.grpc.Metadata;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Executes a UnifiedMatcher against a request.
 */
public final class MatcherRunner {
  private MatcherRunner() {}

  /**
   * runs the matcher.
   */
  @Nullable
  public static List<TypedExtensionConfig> checkMatch(
      Matcher proto, MatchContext context) {
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchResult result = matcher.match(context, 0);
    if (!result.matched) {
      return null;
    }
    
    List<TypedExtensionConfig> allActions = 
        new ArrayList<>(result.keepMatchingActions);
    if (result.action != null) {
      allActions.add(result.action);
    }
    
    if (allActions.isEmpty()) {
      return null;
    }
    return allActions;
  }

  public static final class MatchContext {
    private final Metadata metadata;
    @Nullable 
    private final String path;
    @Nullable 
    private final String host;
    @Nullable 
    private final String method;
    @Nullable 
    private final String id;

    public MatchContext(Metadata metadata, @Nullable String path,
        @Nullable String host, @Nullable String method,
        @Nullable String id) {
      this.metadata = Preconditions.checkNotNull(metadata, "metadata");
      this.path = path;
      this.host = host;
      this.method = method;
      this.id = id;
    }

    public Metadata getMetadata() {
      return metadata;
    }
    
    @Nullable
    public String getPath() {
      return path;
    }
    
    @Nullable
    public String getHost() {
      return host;
    }
    
    @Nullable
    public String getMethod() {
      return method;
    }
    
    @Nullable
    public String getId() {
      return id;
    }

    public static Builder newBuilder() {
      return new Builder();
    }

    public static final class Builder {
      private Metadata metadata = new Metadata();
      private String path;
      private String host;
      private String method;
      private String id;

      public Builder setMetadata(Metadata metadata) {
        this.metadata = metadata;
        return this;
      }

      public Builder setPath(String path) {
        this.path = path;
        return this;
      }

      public Builder setHost(String host) {
        this.host = host;
        return this;
      }

      public Builder setMethod(String method) {
        this.method = method;
        return this;
      }

      public Builder setId(String id) {
        this.id = id;
        return this;
      }

      public MatchContext build() {
        return new MatchContext(metadata, path, host, method, id);
      }
    }
  }
}
