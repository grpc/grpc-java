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

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.Executor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CallExecutorsTest {

  @Test
  public void safeguard_alreadySerializing_returnsSameInstance() {
    Executor raw = command -> command.run();
    SerializingExecutor serializing = new SerializingExecutor(raw);
    assertSame(serializing, CallExecutors.safeguard(serializing));
  }

  @Test
  public void safeguard_alreadySerializeReentrantCallsDirect_returnsSameInstance() {
    SerializeReentrantCallsDirectExecutor direct = new SerializeReentrantCallsDirectExecutor();
    assertSame(direct, CallExecutors.safeguard(direct));
  }

  @Test
  public void safeguard_directExecutor_returnsSerializeReentrantCallsDirect() {
    Executor safeguarded = CallExecutors.safeguard(directExecutor());
    assertTrue(safeguarded instanceof SerializeReentrantCallsDirectExecutor);
  }

  @Test
  public void safeguard_otherExecutor_returnsSerializing() {
    Executor raw = command -> command.run();
    Executor safeguarded = CallExecutors.safeguard(raw);
    assertTrue(safeguarded instanceof SerializingExecutor);
  }

  @Test
  public void safeguard_idempotent() {
    Executor raw = command -> command.run();
    Executor first = CallExecutors.safeguard(raw);
    Executor second = CallExecutors.safeguard(first);
    assertSame(first, second);

    Executor firstDirect = CallExecutors.safeguard(directExecutor());
    Executor secondDirect = CallExecutors.safeguard(firstDirect);
    assertSame(firstDirect, secondDirect);
  }
}
