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

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import com.google.re2j.Pattern;
import io.grpc.xds.internal.headermutations.HeaderValueOption.HeaderAppendAction;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HeaderMutationFilterTest {

  private static final int MAX_HEADER_LENGTH = 16384;

  private static HeaderValueOption header(String key, ByteString value) {
    return HeaderValueOption.create(io.grpc.xds.internal.grpcservice.HeaderValue.create(key, value),
        HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD, false);
  }

  private static HeaderValueOption header(String key, String value) {
    return HeaderValueOption.create(io.grpc.xds.internal.grpcservice.HeaderValue.create(key, value),
        HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD, false);
  }

  @Test
  public void filter_validationRules_dropsInvalidHeaders() throws Exception {
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.empty());
    String longString = Strings.repeat("a", MAX_HEADER_LENGTH + 1);
    ByteString longBytes = ByteString.copyFrom(new byte[MAX_HEADER_LENGTH + 1]);

    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(
            header("add-key", "add-value"), header(":authority", "new-authority"),
            header("host", "new-host"), header(":scheme", "https"), header(":method", "PUT"),
            header("resp-add-key", "resp-add-value"), header(":scheme", "https"),
            header(":path", "/new-path"), header(":grpc-trace-bin", "binary-value"),
            header(":alt-svc", "h3=:443"), header("user-agent", "new-agent"),
            header("Valid-Key", "value"), header("", "value"), header(longString, "value"),
            header("long-value-key", longString), header("long-bin-key-bin", longBytes),
            header("grpc-timeout", "10S"), header("valid-key-lower", "value")),
        ImmutableList.of("remove-key", "host", ":authority", ":scheme", ":method", ":foo", ":bar",
            "Valid-Key", "", longString, "grpc-timeout", "UPPER-REMOVE", "lower-remove"));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.headersToRemove()).containsExactly("remove-key", "lower-remove");
    assertThat(filtered.headers()).containsExactly(
        header("add-key", "add-value"), header("resp-add-key", "resp-add-value"),
        header("user-agent", "new-agent"), header("valid-key-lower", "value"));
  }

  @Test
  public void filter_validationRules_throwsOnInvalidHeaders() throws Exception {
    HeaderMutationRulesConfig rules =
        HeaderMutationRulesConfig.builder().disallowIsError(true).build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));
    String longString = Strings.repeat("a", MAX_HEADER_LENGTH + 1);

    // Test system headers modification
    assertThrows(HeaderMutationDisallowedException.class, () -> filter.filter(HeaderMutations
        .create(
            ImmutableList.of(header(":path", "/new-path")), ImmutableList.of())));

    // Test system headers removal
    assertThrows(HeaderMutationDisallowedException.class,
        () -> filter.filter(HeaderMutations.create(
            ImmutableList.of(), ImmutableList.of(":path"))));

    // Test uppercase header modification
    assertThrows(HeaderMutationDisallowedException.class, () -> filter.filter(HeaderMutations
        .create(
            ImmutableList.of(header("Valid-Key", "value")), ImmutableList.of())));

    // Test uppercase header removal
    assertThrows(HeaderMutationDisallowedException.class, () -> filter
        .filter(HeaderMutations.create(
            ImmutableList.of(), ImmutableList.of("UPPER-REMOVE"))));

    // Test empty header
    assertThrows(HeaderMutationDisallowedException.class, () -> filter
        .filter(HeaderMutations.create(
            ImmutableList.of(header("", "value")), ImmutableList.of())));

    // Test long header key
    assertThrows(HeaderMutationDisallowedException.class, () -> filter
        .filter(HeaderMutations.create(
            ImmutableList.of(), ImmutableList.of(longString))));
  }


  @Test
  public void filter_mutationRules_disallowAll_dropsAll() throws Exception {
    HeaderMutationRulesConfig rules = HeaderMutationRulesConfig.builder().disallowAll(true).build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(header("add-key", "add-value"), header("resp-add-key", "resp-add-value")),
        ImmutableList.of("remove-key"));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.headers()).isEmpty();
    assertThat(filtered.headersToRemove()).isEmpty();
  }

  @Test
  public void filter_mutationRules_disallowAll_throws() throws Exception {
    HeaderMutationRulesConfig rules =
        HeaderMutationRulesConfig.builder().disallowAll(true).disallowIsError(true).build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));

    // Test add header
    assertThrows(HeaderMutationDisallowedException.class, () -> filter.filter(HeaderMutations
        .create(
            ImmutableList.of(header("add-key", "add-value")), ImmutableList.of())));

    // Test remove header
    assertThrows(HeaderMutationDisallowedException.class, () -> filter
        .filter(HeaderMutations.create(
            ImmutableList.of(), ImmutableList.of("remove-key"))));

    // Test response header
    assertThrows(HeaderMutationDisallowedException.class, () -> filter.filter(HeaderMutations
        .create(
            ImmutableList.of(header("resp-add-key", "resp-add-value")), ImmutableList.of())));
  }


  @Test
  public void filter_mutationRules_disallowExpression_dropsMatching() throws Exception {
    HeaderMutationRulesConfig rules = HeaderMutationRulesConfig.builder()
        .disallowExpression(Pattern.compile("^x-private-.*")).build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(header("x-public", "value"), header("x-private-key", "value"),
            header("x-public-resp", "value"), header("x-private-resp", "value")),
        ImmutableList.of("x-public-remove", "x-private-remove"));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.headersToRemove()).containsExactly("x-public-remove");
    assertThat(filtered.headers()).containsExactly(header("x-public", "value"),
            header("x-public-resp", "value"));
  }

  @Test
  public void filter_mutationRules_disallowExpression_throws() throws Exception {
    HeaderMutationRulesConfig rules = HeaderMutationRulesConfig.builder()
        .disallowExpression(Pattern.compile("^x-private-.*")).disallowIsError(true).build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));

    // Test disallowed key modification
    assertThrows(HeaderMutationDisallowedException.class, () -> filter.filter(HeaderMutations
        .create(
            ImmutableList.of(header("x-private-key", "value")), ImmutableList.of())));

    // Test disallowed key removal
    assertThrows(HeaderMutationDisallowedException.class, () -> filter
        .filter(HeaderMutations.create(
            ImmutableList.of(), ImmutableList.of("x-private-remove"))));
  }


  @Test
  public void filter_mutationRules_precedence() throws Exception {
    HeaderMutationRulesConfig rules = HeaderMutationRulesConfig.builder()
        .disallowAll(true)
        .allowExpression(Pattern.compile("^x-allowed-.*"))
        .disallowExpression(Pattern.compile("^x-allowed-but-disallowed-.*"))
        .build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));

    // Case 1: allowExpression overrides disallowAll
    HeaderMutations mutations1 = HeaderMutations.create(
        ImmutableList.of(header("x-allowed-key", "value"), header("not-allowed", "value")),
        ImmutableList.of("x-allowed-remove", "not-allowed-remove"));
    HeaderMutations filtered1 = filter.filter(mutations1);
    assertThat(filtered1.headersToRemove()).containsExactly("x-allowed-remove");
    assertThat(filtered1.headers()).containsExactly(header("x-allowed-key", "value"));

    // Case 2: disallowExpression overrides allowExpression
    HeaderMutations mutations2 = HeaderMutations.create(
        ImmutableList.of(header("x-allowed-but-disallowed-key", "value")),
        ImmutableList.of("x-allowed-but-disallowed-remove"));
    HeaderMutations filtered2 = filter.filter(mutations2);
    assertThat(filtered2.headers()).isEmpty();
    assertThat(filtered2.headersToRemove()).isEmpty();
  }

  @Test
  public void filter_mutationRules_precedence_throws() throws Exception {
    // Case 1: allowExpression overrides disallowAll (does not throw)
    HeaderMutationRulesConfig rules1 = HeaderMutationRulesConfig.builder()
        .disallowAll(true)
        .allowExpression(Pattern.compile("^x-allowed-.*"))
        .disallowIsError(true)
        .build();
    HeaderMutationFilter filter1 = new HeaderMutationFilter(Optional.of(rules1));
    HeaderMutations mutations1 = HeaderMutations.create(
        ImmutableList.of(header("x-allowed-key", "value")), ImmutableList.of("x-allowed-remove"));
    HeaderMutations filtered1 = filter1.filter(mutations1);
    assertThat(filtered1.headersToRemove()).containsExactly("x-allowed-remove");
    assertThat(filtered1.headers()).containsExactly(header("x-allowed-key", "value"));

    // Case 2: disallowExpression overrides allowExpression (throws)
    HeaderMutationRulesConfig rules2 = HeaderMutationRulesConfig.builder()
        .allowExpression(Pattern.compile("^x-allowed-.*"))
        .disallowExpression(Pattern.compile("^x-allowed-but-disallowed-.*"))
        .disallowIsError(true)
        .build();
    HeaderMutationFilter filter2 = new HeaderMutationFilter(Optional.of(rules2));
    assertThrows(HeaderMutationDisallowedException.class,
        () -> filter2.filter(HeaderMutations.create(
            ImmutableList.of(header("x-allowed-but-disallowed-key", "value")),
            ImmutableList.of())));

    assertThrows(HeaderMutationDisallowedException.class, () -> filter2.filter(HeaderMutations
        .create(
            ImmutableList.of(), ImmutableList.of("x-allowed-but-disallowed-remove"))));
  }
}
