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
import javax.annotation.Nullable;

public final class MatchContext {
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
