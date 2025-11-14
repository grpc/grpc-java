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

package io.grpc.xds;

import javax.annotation.concurrent.ThreadSafe;

// TODO(sauravzg): Remove this class once all usages within xds are migrated to
// the internal version.
@ThreadSafe
interface ThreadSafeRandom extends io.grpc.xds.internal.ThreadSafeRandom {

  final class ThreadSafeRandomImpl implements ThreadSafeRandom {

    static final ThreadSafeRandom instance = new ThreadSafeRandomImpl();
    private final io.grpc.xds.internal.ThreadSafeRandom delegate =
        io.grpc.xds.internal.ThreadSafeRandom.ThreadSafeRandomImpl.INSTANCE;

    private ThreadSafeRandomImpl() {}

    @Override
    public int nextInt(int bound) {
      return delegate.nextInt(bound);
    }

    @Override
    public long nextLong() {
      return delegate.nextLong();
    }

    @Override
    public long nextLong(long bound) {
      return delegate.nextLong(bound);
    }
  }
}
