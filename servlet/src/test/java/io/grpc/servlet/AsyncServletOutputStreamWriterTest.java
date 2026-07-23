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

package io.grpc.servlet;

import static org.junit.Assert.assertTrue;

import io.grpc.servlet.AsyncServletOutputStreamWriter.ActionItem;
import io.grpc.servlet.AsyncServletOutputStreamWriter.Log;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AsyncServletOutputStreamWriter}. */
@RunWith(JUnit4.class)
public class AsyncServletOutputStreamWriterTest {

  @Test(timeout = 5000)
  public void onWritePossibleWhenAlreadyReadyAndDrainedReturnsImmediately() throws IOException {
    AtomicBoolean written = new AtomicBoolean(false);
    ActionItem writeAction = () -> written.set(true);

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            (bytes, len) -> writeAction,
            () -> { },
            () -> { },
            () -> true,
            new Log() { });

    // Initial onWritePossible call turns readyAndDrained to true
    writer.onWritePossible();

    long startTime = System.nanoTime();
    // Subsequent call when readyAndDrained is true must return non-blockingly
    writer.onWritePossible();
    long durationMs = (System.nanoTime() - startTime) / 1_000_000;

    assertTrue("onWritePossible must not park or block thread", durationMs < 1000);
  }
}
