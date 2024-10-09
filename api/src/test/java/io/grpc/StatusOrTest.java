/*
 * Copyright 2015 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static junit.framework.TestCase.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link StatusOr}. **/
@RunWith(JUnit4.class)
public class StatusOrTest {

  @Test
  public void getValue_throwsIfNoValuePresent() {
    try {
      StatusOr.fromStatus(Status.ABORTED).getValue();

      fail("Expected exception.");
    } catch (IllegalStateException expected) { }
  }

  @Test
  @SuppressWarnings("TruthIncompatibleType")
  public void equals_differentValueTypes() {
    assertThat(StatusOr.fromValue(1)).isNotEqualTo(StatusOr.fromValue("1"));
  }

  @Test
  public void equals_differentValues() {
    assertThat(StatusOr.fromValue(1)).isNotEqualTo(StatusOr.fromValue(2));
  }

  @Test
  public void equals_sameValues() {
    assertThat(StatusOr.fromValue(1)).isEqualTo(StatusOr.fromValue(1));
  }

  @Test
  public void equals_differentStatuses() {
    assertThat(StatusOr.fromStatus(Status.ABORTED)).isNotEqualTo(
        StatusOr.fromStatus(Status.CANCELLED));
  }

  @Test
  public void equals_sameStatuses() {
    assertThat(StatusOr.fromStatus(Status.ABORTED)).isEqualTo(StatusOr.fromStatus(Status.ABORTED));
  }

  @Test
  public void toString_value() {
    assertThat(StatusOr.fromValue(1).toString()).isEqualTo("StatusOr{value=1}");
  }

  @Test
  public void toString_nullValue() {
    assertThat(StatusOr.fromValue(null).toString()).isEqualTo("StatusOr{value=null}");
  }

  @Test
  public void toString_errorStatus() {
    assertThat(StatusOr.fromStatus(Status.ABORTED).toString()).isEqualTo(
        "StatusOr{error=Status{code=ABORTED, description=null, cause=null}}");
  }
}