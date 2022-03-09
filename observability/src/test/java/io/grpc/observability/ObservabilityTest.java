/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.observability;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ObservabilityTest {
  
  @Test
  public void initFinish() {
    Observability.grpcInit();
    try {
      Observability.grpcInit();
      fail("should have failed for calling grpcInit() again");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("Observability already initialized!");
    }
    Observability.grpcFinish();
    try {
      Observability.grpcFinish();
      fail("should have failed for calling grpcFinit() on uninitialized");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("Observability not initialized!");
    }
  }
}
