/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc;

import javax.annotation.Nullable;

/**
 * This is an gRPC internal interface. Do not use this.
 *
 * <p>An interface for types that <b>may</b> support instrumentation. If the concrete
 * type does not support instrumentation, then the stat passed to {@link Consumer}
 * will be {@code null}. The actual consumption of the stat T does not necessarily happen
 * on the current thread.
 */
@Internal
public interface InternalInstrumented<T> extends InternalWithLogId {
  void produceStat(Consumer<T> consumer);

  /**
   * An interface for consuming an arbitrary stat of type T.
   */
  interface Consumer<T> {
    void consume(@Nullable T stat);
  }
}
