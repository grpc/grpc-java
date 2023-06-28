/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.binder;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BindServiceFlagsTest {

  @Test
  public void shouldAutoCreateByDefault() {
    assertThat(BindServiceFlags.DEFAULTS.toInteger() & Context.BIND_AUTO_CREATE).isNotEqualTo(0);
  }

  @Test
  public void shouldCheckForInequality() {
    assertThat(BindServiceFlags.newBuilder().setAutoCreate(true).build())
        .isNotEqualTo(BindServiceFlags.newBuilder().build());
  }

  @Test
  public void shouldCheckForEquality() {
    assertThat(BindServiceFlags.DEFAULTS).isEqualTo(BindServiceFlags.DEFAULTS.toBuilder().build());
  }

  @Test
  public void shouldReflectResetFlags() {
    assertThat(
            BindServiceFlags.newBuilder()
                .setAutoCreate(true)
                .setAutoCreate(false)
                .setAutoCreate(true)
                .build()
                .toInteger())
        .isEqualTo(Context.BIND_AUTO_CREATE);
  }

  @Test
  public void shouldReflectReclearedFlags() {
    assertThat(
            BindServiceFlags.newBuilder()
                .setAutoCreate(false)
                .setAutoCreate(true)
                .setAutoCreate(false)
                .build()
                .toInteger())
        .isEqualTo(0);
  }

  @Test
  public void shouldReflectSetFlags() {
    assertThat(
            BindServiceFlags.newBuilder()
                .setAutoCreate(true)
                .setAdjustWithActivity(true)
                .setAboveClient(true)
                .setAllowActivityStarts(true)
                .setAllowOomManagement(true)
                .setImportant(true)
                .setIncludeCapabilities(true)
                .setNotForeground(true)
                .setNotPerceptible(true)
                .setWaivePriority(true)
                .build()
                .toInteger())
        .isEqualTo(
            Context.BIND_AUTO_CREATE
                | Context.BIND_ADJUST_WITH_ACTIVITY
                | Context.BIND_ABOVE_CLIENT
                | Context.BIND_ALLOW_OOM_MANAGEMENT
                | Context.BIND_IMPORTANT
                | Context.BIND_INCLUDE_CAPABILITIES
                | Context.BIND_NOT_FOREGROUND
                | Context.BIND_NOT_PERCEPTIBLE
                | Context.BIND_WAIVE_PRIORITY
                // TODO(b/274061424): Use Context.BIND_ALLOW_ACTIVITY_STARTS when U is final.
                | 0x200);
  }
}
