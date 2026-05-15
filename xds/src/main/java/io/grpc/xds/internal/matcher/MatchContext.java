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

public final class MatchContext {
  private final Metadata metadata;
  private final String path;
  private final String host;
  private final String method;

  public MatchContext(Metadata metadata, String path,
      String host, String method) {
    this.metadata = Preconditions.checkNotNull(metadata, "metadata");
    this.path = Preconditions.checkNotNull(path, "path");
    this.host = Preconditions.checkNotNull(host, "host");
    this.method = Preconditions.checkNotNull(method, "method");
  }

  public Metadata getMetadata() {
    return metadata;
  }
  
  public String getPath() {
    return path;
  }
  
  public String getHost() {
    return host;
  }
  
  public String getMethod() {
    return method;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private Metadata metadata = new Metadata();
    private String path;
    private String host;
    private String method;

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

    public MatchContext build() {
      return new MatchContext(metadata, path, host, method);
    }
  }
}
