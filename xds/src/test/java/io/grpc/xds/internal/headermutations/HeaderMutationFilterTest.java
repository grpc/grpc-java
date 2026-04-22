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

import com.google.common.collect.ImmutableList;
import com.google.re2j.Pattern;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderValueOption.HeaderAppendAction;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HeaderMutationFilterTest {

  private static HeaderValueOption header(String key, String value) {
    return HeaderValueOption.create(io.grpc.xds.internal.grpcservice.HeaderValue.create(key, value),
            HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD, false);
  }

  private static HeaderValueOption header(String key, String value, HeaderAppendAction action) {
    return HeaderValueOption.create(io.grpc.xds.internal.grpcservice.HeaderValue.create(key, value),
        action,
            false);
  }

  @Test
  public void filter_removesImmutableHeaders() throws HeaderMutationDisallowedException {
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.empty());
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(header("add-key", "add-value"), header(":authority", "new-authority"),
            header("host", "new-host"), header(":scheme", "https"), header(":method", "PUT"),
            header("resp-add-key", "resp-add-value"), header(":scheme", "https")),
        ImmutableList.of("remove-key", "host", ":authority", ":scheme", ":method"));

    HeaderMutations filtered = filter.filter(mutations);


    assertThat(filtered.headersToRemove()).containsExactly("remove-key");
    assertThat(filtered.headers()).containsExactly(header("add-key", "add-value"),
            header("resp-add-key", "resp-add-value"));
  }

  @Test
  public void filter_cannotAppendToSystemHeaders() throws HeaderMutationDisallowedException {
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.empty());
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(
            header("add-key", "add-value", HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD),
            header(":authority", "new-authority", HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD),
            header("host", "new-host", HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD),
            header(":path", "/new-path", HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD),
            header("host", "new-host", HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD)),
        ImmutableList.of());

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.headers()).containsExactly(
        header("add-key", "add-value", HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD));
  }

  @Test
  public void filter_cannotRemoveSystemHeaders() throws HeaderMutationDisallowedException {
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.empty());
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(),
        ImmutableList.of("remove-key", "host", ":foo", ":bar"));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.headersToRemove()).containsExactly("remove-key");
  }

  @Test
  public void filter_cannotOverrideSystemHeaders()
      throws HeaderMutationDisallowedException {
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.empty());
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(header("user-agent", "new-agent"),
            header(":path", "/new/path", HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD),
            header(":grpc-trace-bin", "binary-value", HeaderAppendAction.ADD_IF_ABSENT),
            header(":alt-svc", "h3=:443", HeaderAppendAction.OVERWRITE_IF_EXISTS)),
        ImmutableList.of());

    HeaderMutations filtered = filter.filter(mutations);

    // System headers should be filtered out
    assertThat(filtered.headers()).containsExactly(
        header("user-agent", "new-agent"));
  }

  @Test
  public void filter_disallowAll_disablesAllModifications()
      throws HeaderMutationDisallowedException {
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
  public void filter_disallowExpression_filtersRelevantExpressions()
      throws HeaderMutationDisallowedException {
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
  public void filter_allowExpression_onlyAllowsRelevantExpressions()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules = HeaderMutationRulesConfig.builder()
        .allowExpression(Pattern.compile("^x-allowed-.*")).build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(header("x-allowed-key", "value"), header("not-allowed-key", "value"),
            header("x-allowed-resp-key", "value"), header("not-allowed-resp-key", "value")),
        ImmutableList.of("x-allowed-remove", "not-allowed-remove"));

    HeaderMutations filtered = filter.filter(mutations);


    assertThat(filtered.headersToRemove()).containsExactly("x-allowed-remove",
        "not-allowed-remove");
    assertThat(filtered.headers()).containsExactly(header("x-allowed-key", "value"),
        header("not-allowed-key", "value"), header("x-allowed-resp-key", "value"),
        header("not-allowed-resp-key", "value"));
  }

  @Test
  public void filter_allowExpression_fallsThroughToDisallowAll()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules = HeaderMutationRulesConfig.builder().disallowAll(true)
        .allowExpression(Pattern.compile("^x-allowed-.*")).build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(header("x-allowed-key", "value"), header("not-allowed-key", "value")),
        ImmutableList.of("x-allowed-remove", "not-allowed-remove"));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.headersToRemove()).containsExactly("x-allowed-remove");
    assertThat(filtered.headers()).containsExactly(header("x-allowed-key", "value"));
  }


  @Test
  public void filter_allowExpression_overridesDisallowAll()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules = HeaderMutationRulesConfig.builder().disallowAll(true)
        .allowExpression(Pattern.compile("^x-allowed-.*")).build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(header("x-allowed-key", "value"), header("not-allowed", "value"),
            header("x-allowed-resp-key", "value"), header("not-allowed-resp-key", "value")),
        ImmutableList.of("x-allowed-remove", "not-allowed-remove"));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.headersToRemove()).containsExactly("x-allowed-remove");
    assertThat(filtered.headers()).containsExactly(header("x-allowed-key", "value"),
            header("x-allowed-resp-key", "value"));
  }

  @Test(expected = HeaderMutationDisallowedException.class)
  public void filter_disallowIsError_throwsExceptionOnDisallowed()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules =
        HeaderMutationRulesConfig.builder().disallowAll(true).disallowIsError(true).build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(header("add-key", "add-value")),
        ImmutableList.of());
    filter.filter(mutations);
  }

  @Test(expected = HeaderMutationDisallowedException.class)
  public void filter_disallowIsError_throwsExceptionOnDisallowedRemove()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules =
        HeaderMutationRulesConfig.builder().disallowAll(true).disallowIsError(true).build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(),
        ImmutableList.of("remove-key"));
    filter.filter(mutations);
  }

  @Test(expected = HeaderMutationDisallowedException.class)
  public void filter_disallowIsError_throwsExceptionOnDisallowedResponseHeader()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules =
        HeaderMutationRulesConfig.builder().disallowAll(true).disallowIsError(true).build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(header("resp-add-key", "resp-add-value")),
        ImmutableList.of());
    filter.filter(mutations);
  }

  @Test
  public void filter_ignoresUppercaseHeaders() throws HeaderMutationDisallowedException {
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.empty());
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(header("Valid-Key", "value"), header("valid-key", "value")),
        ImmutableList.of("UPPER-REMOVE", "lower-remove"));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.headers()).containsExactly(header("valid-key", "value"));
    assertThat(filtered.headersToRemove()).containsExactly("lower-remove");
  }

  @Test(expected = HeaderMutationDisallowedException.class)
  public void filter_disallowIsError_throwsExceptionOnUppercaseHeaders()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules =
        HeaderMutationRulesConfig.builder().disallowIsError(true).build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(header("Valid-Key", "value")),
        ImmutableList.of());
    filter.filter(mutations);
  }

  @Test(expected = HeaderMutationDisallowedException.class)
  public void filter_disallowIsError_throwsExceptionOnSystemHeadersRemoval()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules =
        HeaderMutationRulesConfig.builder().disallowIsError(true).build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(),
        ImmutableList.of(":path"));
    filter.filter(mutations);
  }

  @Test(expected = HeaderMutationDisallowedException.class)
  public void filter_disallowIsError_throwsExceptionOnSystemHeadersModification()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules =
        HeaderMutationRulesConfig.builder().disallowIsError(true).build();
    HeaderMutationFilter filter = new HeaderMutationFilter(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        ImmutableList.of(header(":path", "/new-path")),
        ImmutableList.of());
    filter.filter(mutations);
  }
}
