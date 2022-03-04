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

package io.grpc.observability.interceptors.logging;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import io.grpc.observability.logging.GcpLogSink;
import io.grpc.observability.logging.Sink;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link io.grpc.observability.logging.GcpLogSink}.
 */
@RunWith(JUnit4.class)
public class GcpLogSinkTest {

  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  @Test
  public void initTwiceCausesException() {
    Sink initializedSink = GcpLogSink.init(null);
    Sink getInstanceSink = GcpLogSink.getInstance();
    assertThat(initializedSink).isSameInstanceAs(getInstanceSink);
    try {
      GcpLogSink.init(null);
      fail("Failed for calling init() again");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("GcpLogSink already initialized");
    }
    GcpLogSink.getInstance().close();
  }

  @Test
  public void getInstanceWithoutInitCausesException() {
    try {
      GcpLogSink.getInstance();
      fail("Failed for calling getInstance without calling init before");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("GcpLogSink not initialized");
    }
  }
}
