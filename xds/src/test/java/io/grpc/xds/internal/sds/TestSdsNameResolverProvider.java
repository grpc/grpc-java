/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds.internal.sds;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import io.grpc.InternalServiceProviders;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.internal.GrpcUtil;
import java.net.URI;

public final class TestSdsNameResolverProvider extends NameResolverProvider {

  private static final String SCHEME = "sdstest";

  TestSdsNameResolver.Callback callback;

  public TestSdsNameResolverProvider(TestSdsNameResolver.Callback callback) {
    this.callback = callback;
  }

  @Override
  public TestSdsNameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
    if (SCHEME.equals(targetUri.getScheme())) {
      String targetPath = Preconditions.checkNotNull(targetUri.getPath(), "targetPath");
      Preconditions.checkArgument(
          targetPath.startsWith("/"),
          "the path component (%s) of the target (%s) must start with '/'",
          targetPath,
          targetUri);
      String name = targetPath.substring(1);
      return new TestSdsNameResolver(
          targetUri.getAuthority(),
          name,
          args,
          GrpcUtil.SHARED_CHANNEL_EXECUTOR,
          Stopwatch.createUnstarted(),
          InternalServiceProviders.isAndroid(getClass().getClassLoader()),
          callback);
    } else {
      return null;
    }
  }

  @Override
  protected boolean isAvailable() {
    return true;
  }

  @Override
  protected int priority() {
    return 3;
  }

  @Override
  public String getDefaultScheme() {
    return SCHEME;
  }
}
