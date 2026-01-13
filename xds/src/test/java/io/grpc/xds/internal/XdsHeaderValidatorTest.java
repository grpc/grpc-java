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

package io.grpc.xds.internal;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Strings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class XdsHeaderValidatorTest {

  @Test
  public void isValid_validKeyAndLength_returnsTrue() {
    assertThat(XdsHeaderValidator.isValid("valid-key", 10)).isTrue();
  }

  @Test
  public void isValid_emptyKey_returnsFalse() {
    assertThat(XdsHeaderValidator.isValid("", 10)).isFalse();
  }

  @Test
  public void isValid_uppercaseKey_returnsFalse() {
    assertThat(XdsHeaderValidator.isValid("Invalid-Key", 10)).isFalse();
  }

  @Test
  public void isValid_keyExceedsMaxLength_returnsFalse() {
    String longKey = Strings.repeat("k", 16385);
    assertThat(XdsHeaderValidator.isValid(longKey, 10)).isFalse();
  }

  @Test
  public void isValid_valueExceedsMaxLength_returnsFalse() {
    assertThat(XdsHeaderValidator.isValid("valid-key", 16385)).isFalse();
  }

  @Test
  public void isValid_hostKey_returnsFalse() {
    assertThat(XdsHeaderValidator.isValid("host", 10)).isFalse();
  }

  @Test
  public void isValid_pseudoHeaderKey_returnsFalse() {
    assertThat(XdsHeaderValidator.isValid(":method", 10)).isFalse();
  }
}
