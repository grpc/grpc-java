/*
 * Copyright 2025 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.NameResolver;
import io.grpc.Uri;
import java.net.URI;
import javax.annotation.Nullable;

/** Temporary wrapper for a URI-like object to ease the migration to io.grpc.Uri. */
interface UriWrapper {

  static UriWrapper wrap(URI uri) {
    return new JavaNetUriWrapper(uri);
  }

  static UriWrapper wrap(Uri uri) {
    return new IoGrpcUriWrapper(uri);
  }

  /** Uses the given factory and args to create a {@link NameResolver} for this URI. */
  NameResolver newNameResolver(NameResolver.Factory factory, NameResolver.Args args);

  /** Returns the scheme component of this URI, e.g. "http", "mailto" or "dns". */
  String getScheme();

  /**
   * Returns the authority component of this URI, e.g. "google.com", "127.0.0.1:8080", or null if
   * not present.
   */
  @Nullable
  String getAuthority();

  /** Wraps an instance of java.net.URI. */
  final class JavaNetUriWrapper implements UriWrapper {
    private final URI uri;

    private JavaNetUriWrapper(URI uri) {
      this.uri = checkNotNull(uri);
    }

    @Override
    public NameResolver newNameResolver(NameResolver.Factory factory, NameResolver.Args args) {
      return factory.newNameResolver(uri, args);
    }

    @Override
    public String getScheme() {
      return uri.getScheme();
    }

    @Override
    public String getAuthority() {
      return uri.getAuthority();
    }

    @Override
    public String toString() {
      return uri.toString();
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      if (!(other instanceof JavaNetUriWrapper)) {
        return false;
      }
      return uri.equals(((JavaNetUriWrapper) other).uri);
    }

    @Override
    public int hashCode() {
      return uri.hashCode();
    }
  }

  /** Wraps an instance of io.grpc.Uri. */
  final class IoGrpcUriWrapper implements UriWrapper {
    private final Uri uri;

    private IoGrpcUriWrapper(Uri uri) {
      this.uri = checkNotNull(uri);
    }

    @Override
    public NameResolver newNameResolver(NameResolver.Factory factory, NameResolver.Args args) {
      return factory.newNameResolver(uri, args);
    }

    @Override
    public String getScheme() {
      return uri.getScheme();
    }

    @Override
    public String getAuthority() {
      return uri.getAuthority();
    }

    @Override
    public String toString() {
      return uri.toString();
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      if (!(other instanceof IoGrpcUriWrapper)) {
        return false;
      }
      return uri.equals(((IoGrpcUriWrapper) other).uri);
    }

    @Override
    public int hashCode() {
      return uri.hashCode();
    }
  }
}
