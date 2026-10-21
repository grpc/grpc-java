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

final class MatchContext {
  private final Metadata metadata;
  private final String path;
  private final String host;
  private final String method;
  private final String id;

  MatchContext(Metadata metadata, String path,
      String host, String method,
      String id) {
    this.metadata = Preconditions.checkNotNull(metadata, "metadata");
    this.path = path;
    this.host = host;
    this.method = method;
    this.id = id;
  }

  Metadata getMetadata() {
    return metadata;
  }
  
  String getPath() {
    return path;
  }
  
  String getHost() {
    return host;
  }
  
  String getMethod() {
    return method;
  }
  
  String getId() {
    return id;
  }

  static Builder newBuilder() {
    return new Builder();
  }

  static final class Builder {
    private Metadata metadata = new Metadata();
    private String path;
    private String host;
    private String method;
    private String id;

    Builder setMetadata(Metadata metadata) {
      this.metadata = metadata;
      return this;
    }

    Builder setPath(String path) {
      this.path = path;
      return this;
    }

    Builder setHost(String host) {
      this.host = host;
      return this;
    }

    Builder setMethod(String method) {
      this.method = method;
      return this;
    }

    Builder setId(String id) {
      this.id = id;
      return this;
    }

    MatchContext build() {
      return new MatchContext(metadata, path, host, method, id);
    }
  }
}
