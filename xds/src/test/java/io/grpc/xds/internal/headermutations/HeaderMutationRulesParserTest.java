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
import static org.junit.Assert.assertThrows;

import com.google.protobuf.BoolValue;
import io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesParseException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HeaderMutationRulesParserTest {

  @Test
  public void parse_protoWithAllFields_success() throws Exception {
    HeaderMutationRules proto = HeaderMutationRules.newBuilder()
        .setAllowExpression(RegexMatcher.newBuilder().setRegex("allow-.*"))
        .setDisallowExpression(RegexMatcher.newBuilder().setRegex("disallow-.*"))
        .setDisallowAll(BoolValue.newBuilder().setValue(true).build())
        .setDisallowIsError(BoolValue.newBuilder().setValue(true).build())
        .build();

    HeaderMutationRulesConfig config = HeaderMutationRulesParser.parse(proto);

    assertThat(config.allowExpression().isPresent()).isTrue();
    assertThat(config.allowExpression().get().pattern()).isEqualTo("allow-.*");

    assertThat(config.disallowExpression().isPresent()).isTrue();
    assertThat(config.disallowExpression().get().pattern()).isEqualTo("disallow-.*");

    assertThat(config.disallowAll()).isTrue();
    assertThat(config.disallowIsError()).isTrue();
  }

  @Test
  public void parse_protoWithNoExpressions_success() throws Exception {
    HeaderMutationRules proto = HeaderMutationRules.newBuilder().build();

    HeaderMutationRulesConfig config = HeaderMutationRulesParser.parse(proto);

    assertThat(config.allowExpression().isPresent()).isFalse();
    assertThat(config.disallowExpression().isPresent()).isFalse();
    assertThat(config.disallowAll()).isFalse();
    assertThat(config.disallowIsError()).isFalse();
  }

  @Test
  public void parse_invalidRegexAllowExpression_throwsHeaderMutationRulesParseException() {
    HeaderMutationRules proto = HeaderMutationRules.newBuilder()
        .setAllowExpression(RegexMatcher.newBuilder().setRegex("allow-["))
        .build();

    HeaderMutationRulesParseException exception = assertThrows(
        HeaderMutationRulesParseException.class, () -> HeaderMutationRulesParser.parse(proto));

    assertThat(exception).hasMessageThat().contains("Invalid regex pattern for allow_expression");
  }

  @Test
  public void parse_invalidRegexDisallowExpression_throwsHeaderMutationRulesParseException() {
    HeaderMutationRules proto = HeaderMutationRules.newBuilder()
        .setDisallowExpression(RegexMatcher.newBuilder().setRegex("disallow-["))
        .build();

    HeaderMutationRulesParseException exception = assertThrows(
        HeaderMutationRulesParseException.class, () -> HeaderMutationRulesParser.parse(proto));

    assertThat(exception).hasMessageThat()
        .contains("Invalid regex pattern for disallow_expression");
  }
}
