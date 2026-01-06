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

package io.grpc.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.rules.ExternalResource;

/**
 * A {@link org.junit.rules.TestRule} that lets you set one or more feature flags just for
 * the duration of the current test case.
 *
 * <p>Flags and other global variables must be reset to ensure no state leaks across tests.
 */
public final class FlagResetRule extends ExternalResource {

  /** A functional interface representing a standard gRPC feature flag setter. */
  public interface SetterMethod<T> {
    /** Sets a flag for testing and returns its previous value. */
    T set(T val);
  }

  private final Deque<Runnable> toRunAfter = new ArrayDeque<>();

  /**
   * Sets a global feature flag to 'value' using 'setter' and arranges for its previous value to be
   * unconditionally restored when the test completes.
   */
  public <T> void setFlagForTest(SetterMethod<T> setter, T value) {
    final T oldValue = setter.set(value);
    toRunAfter.push(() -> setter.set(oldValue));
  }

  @Override
  protected void after() {
    RuntimeException toThrow = null;
    while (!toRunAfter.isEmpty()) {
      try {
        toRunAfter.pop().run();
      } catch (RuntimeException e) {
        if (toThrow == null) {
          toThrow = e;
        } else {
          toThrow.addSuppressed(e);
        }
      }
    }
    if (toThrow != null) {
      throw toThrow;
    }
  }
}
