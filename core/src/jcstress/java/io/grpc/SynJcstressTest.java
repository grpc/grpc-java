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

package io.grpc;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Jcstress test for {@link SynchronizationContext}.
 */
@JCStressTest
// Outline the outcomes here. The default outcome is provided, you need to remove it:
@Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "Default outcome.")
@State
public class SynJcstressTest {

  private static class TestRunnable implements Runnable {
    boolean drained;

    @Override
    public void run() {
      drained = true;
    }
  }

  private final TestRunnable task1 = new TestRunnable();
  private final TestRunnable task2 = new TestRunnable();

  private final SynchronizationContext syncCtx =
      new SynchronizationContext(Thread.getDefaultUncaughtExceptionHandler());

  /** Actor 1. */
  @Actor
  public void actor1(II_Result r) {
    // syncCtx.execute(task1);
  }

  /** Actor 2. */
  @Actor
  public void actor2(II_Result r) {
    // syncCtx.executeLater(task2);
    // syncCtx.drain();
  }

  /** Arbiter. */
  @Arbiter
  public void arbiter(II_Result r) {
    if (task1.drained) {
      r.r1 = 1;
    }
    if (task2.drained) {
      r.r2 = 1;
    }
  }
}