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

package io.grpc.xds.internal.headermutations;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HeaderMutationRulesConfigTest {
  @Test
  public void testBuilderDefaultValues() {
    HeaderMutationRulesConfig config = HeaderMutationRulesConfig.builder().build();
    assertFalse(config.disallowAll());
    assertFalse(config.disallowIsError());
    assertThat(config.allowExpression()).isEmpty();
    assertThat(config.disallowExpression()).isEmpty();
  }

  @Test
  public void testBuilder_setDisallowAll() {
    HeaderMutationRulesConfig config =
        HeaderMutationRulesConfig.builder().disallowAll(true).build();
    assertTrue(config.disallowAll());
  }

  @Test
  public void testBuilder_setDisallowIsError() {
    HeaderMutationRulesConfig config =
        HeaderMutationRulesConfig.builder().disallowIsError(true).build();
    assertTrue(config.disallowIsError());
  }

  @Test
  public void testBuilder_setAllowExpression() {
    Pattern pattern = Pattern.compile("allow.*");
    HeaderMutationRulesConfig config =
        HeaderMutationRulesConfig.builder().allowExpression(pattern).build();
    assertThat(config.allowExpression()).hasValue(pattern);
  }

  @Test
  public void testBuilder_setDisallowExpression() {
    Pattern pattern = Pattern.compile("disallow.*");
    HeaderMutationRulesConfig config =
        HeaderMutationRulesConfig.builder().disallowExpression(pattern).build();
    assertThat(config.disallowExpression()).hasValue(pattern);
  }

  @Test
  public void testBuilder_setAll() {
    Pattern allowPattern = Pattern.compile("allow.*");
    Pattern disallowPattern = Pattern.compile("disallow.*");
    HeaderMutationRulesConfig config = HeaderMutationRulesConfig.builder()
        .disallowAll(true)
        .disallowIsError(true)
        .allowExpression(allowPattern)
        .disallowExpression(disallowPattern)
        .build();
    assertTrue(config.disallowAll());
    assertTrue(config.disallowIsError());
    assertThat(config.allowExpression()).hasValue(allowPattern);
    assertThat(config.disallowExpression()).hasValue(disallowPattern);
  }
}
