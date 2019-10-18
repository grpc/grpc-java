/*
 * Copyright 2019 The gRPC Authors
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import io.grpc.InternalServiceProviders;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import java.net.URI;

/**
 * Base provider of name resolvers for name agnostic consumption.
 */
public abstract class BaseDnsNameResolverProvider extends NameResolverProvider {

  private static final String SCHEME = "dns";

  @VisibleForTesting
  public static final String ENABLE_GRPCLB_PROPERTY_NAME =
      "io.grpc.internal.DnsNameResolverProvider.enable_grpclb";

  /** Returns boolean value of system property {@link #ENABLE_GRPCLB_PROPERTY_NAME}. */
  protected abstract boolean isSrvEnabled();

  @Override
  public DnsNameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
    if (SCHEME.equals(targetUri.getScheme())) {
      String targetPath = Preconditions.checkNotNull(targetUri.getPath(), "targetPath");
      Preconditions.checkArgument(targetPath.startsWith("/"),
          "the path component (%s) of the target (%s) must start with '/'", targetPath, targetUri);
      String name = targetPath.substring(1);
      return new DnsNameResolver(
          targetUri.getAuthority(),
          name,
          args,
          GrpcUtil.SHARED_CHANNEL_EXECUTOR,
          Stopwatch.createUnstarted(),
          InternalServiceProviders.isAndroid(getClass().getClassLoader()),
          isSrvEnabled());
    } else {
      return null;
    }
  }

  @Override
  public String getDefaultScheme() {
    return SCHEME;
  }

  @Override
  protected boolean isAvailable() {
    return true;
  }
}
