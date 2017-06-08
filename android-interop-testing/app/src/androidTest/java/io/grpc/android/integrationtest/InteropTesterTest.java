/*
 * Copyright 2015, gRPC Authors All rights reserved.
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

package io.grpc.android.integrationtest;

import static junit.framework.Assert.assertTrue;

import android.support.test.runner.AndroidJUnit4;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class InteropTesterTest {
  private final int TIMEOUT_SECONDS = 120;

  @Test
  public void interopTests() throws Exception {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicBoolean passed = new AtomicBoolean(false);
    new InteropTester(
            "all",
            TesterOkHttpChannelBuilder.build(
                "grpc-test.sandbox.googleapis.com", 443, null, true, null, null),
            new InteropTester.TestListener() {
              @Override
              public void onPreTest() {}

              @Override
              public void onPostTest(String result) {
                if (result.equals(InteropTester.SUCCESS_MESSAGE)) {
                  passed.set(true);
                }
                latch.countDown();
              }
            },
            false)
        .execute();
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertTrue(passed.get());
  }
}
