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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.internal.SerializingExecutor.SerializingExecutorFactory;
import java.util.concurrent.Executor;

/**
 * Helper class for {@link SerializingExecutor}.
 */
public final class SerializingExecutors {

  /**
   * Creates a serializing executor that executes all runnables on the given delegate.
   */
  public static SerializingExecutor wrap(Executor delegate) {
    if (delegate instanceof SerializingExecutor) {
      return (SerializingExecutor) delegate;
    }
    return wrapFactory(delegate).getExecutor();
  }

  /**
   * Creates a serializing executor factory.  The {@link SerializingExecutor}s crated will execute
   * all runnables on the given delegate.
   */
  public static SerializingExecutorFactory wrapFactory(Executor delegate) {
    checkNotNull(delegate, "delegate");
    if (delegate == MoreExecutors.directExecutor()) {
      return SerializeReentrantCallsDirectExecutorFactory.INSTANCE;
    }
    if (delegate instanceof SerializingExecutor) {
      return new EchoingSerializingExecutorFactory((SerializingExecutor) delegate);
    }
    return SerializingExecutorFab.newInstance(delegate);
  }

  private enum SerializeReentrantCallsDirectExecutorFactory implements SerializingExecutorFactory {
    INSTANCE;

    @Override
    public SerializeReentrantCallsDirectExecutor getExecutor() {
      return new SerializeReentrantCallsDirectExecutor();
    }
  }

  private static final class EchoingSerializingExecutorFactory
      implements SerializingExecutorFactory {

    private final SerializingExecutor delegate;

    EchoingSerializingExecutorFactory(SerializingExecutor delegate) {
      this.delegate = delegate;
    }

    @Override
    public SerializingExecutor getExecutor() {
      return delegate;
    }
  }

  private SerializingExecutors() {
    throw new AssertionError();
  }

}
