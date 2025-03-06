/*
 * Copyright 2025 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RouterFilter}. */
@RunWith(JUnit4.class)
public class RouterFilterTest {
  private static final RouterFilter.Provider FILTER_PROVIDER = new RouterFilter.Provider();

  @Test
  public void filterType_clientAndServer() {
    assertThat(FILTER_PROVIDER.isClientFilter()).isTrue();
    assertThat(FILTER_PROVIDER.isServerFilter()).isTrue();
  }

}
