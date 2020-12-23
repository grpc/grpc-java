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

package io.grpc.testing.integration;

import io.grpc.ManagedChannelBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for integration between {@link ManagedChannelBuilder} and providers. */
@RunWith(JUnit4.class)
public final class ManagedChannelBuilderSpiTest {
  @Test
  public void forAddress_isntObviouslyBroken() {
    ManagedChannelBuilder.forAddress("localhost", 443).build().shutdownNow();
  }

  @Test
  public void forTarget_isntObviouslyBroken() {
    ManagedChannelBuilder.forTarget("localhost:443").build().shutdownNow();
  }
}
