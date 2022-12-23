/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Forked from OkHttp 2.7.0 com.squareup.okhttp.Request
 */
package io.grpc.okhttp.internal.proxy;

import io.grpc.okhttp.internal.Headers;

/**
 * An HTTP ProxyRequest. Instances of this class are immutable.
 */
public final class Request {
  private final HttpUrl url;
  private final Headers headers;

  private Request(Builder builder) {
    this.url = builder.url;
    this.headers = builder.headers.build();
  }

  public HttpUrl httpUrl() {
    return url;
  }

  public Headers headers() {
    return headers;
  }

  public Builder newBuilder() {
    return new Builder();
  }


  @Override public String toString() {
    return "Request{"
        + "url=" + url +
        '}';
  }

  public static class Builder {
    private HttpUrl url;
    private Headers.Builder headers;

    public Builder() {
     this.headers = new Headers.Builder();
    }

    public Builder url(HttpUrl url) {
      if (url == null) throw new IllegalArgumentException("url == null");
      this.url = url;
      return this;
    }

    /**
     * Sets the header named {@code name} to {@code value}. If this request
     * already has any headers with that name, they are all replaced.
     */
    public Builder header(String name, String value) {
      headers.set(name, value);
      return this;
    }

    public Request build() {
      if (url == null) throw new IllegalStateException("url == null");
      return new Request(this);
    }
  }
}
