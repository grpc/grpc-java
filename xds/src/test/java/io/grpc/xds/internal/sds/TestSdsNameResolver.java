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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Stopwatch;
import io.grpc.Status;
import io.grpc.internal.DnsNameResolver;
import io.grpc.internal.SharedResourceHolder;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

public class TestSdsNameResolver extends DnsNameResolver {

  private Callback callback;

  protected TestSdsNameResolver(
      @Nullable String nsAuthority,
      String name,
      Args args,
      SharedResourceHolder.Resource<Executor> executorResource,
      Stopwatch stopwatch,
      boolean isAndroid,
      Callback callback) {
    super(nsAuthority, name, args, executorResource, stopwatch, isAndroid);
    this.callback = checkNotNull(callback, "onResult");
  }

  @Override
  public void start(Listener2 listener) {
    super.start(new TestSdsNameResolverListener(listener));
  }

  public interface Callback {

    ResolutionResult onResult(ResolutionResult resolutionResult);
  }

  private final class TestSdsNameResolverListener extends Listener2 {

    Listener2 chainedListener;

    TestSdsNameResolverListener(Listener2 listener) {
      chainedListener = listener;
    }

    @Override
    public void onResult(ResolutionResult resolutionResult) {
      chainedListener.onResult(callback.onResult(resolutionResult));
    }

    @Override
    public void onError(Status error) {
      chainedListener.onError(error);
    }
  }
}
