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
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption.HeaderAppendAction;
import io.grpc.xds.internal.headermutations.HeaderMutations.RequestHeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutations.ResponseHeaderMutations;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HeaderMutationFilterTest {

  private static HeaderValueOption header(String key, String value) {
    return HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey(key).setValue(value)).build();
  }

  private static HeaderValueOption header(String key, String value, HeaderAppendAction action) {
    return HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey(key).setValue(value)).setAppendAction(action)
        .build();
  }

  @Test
  public void filter_removesImmutableHeaders() throws HeaderMutationDisallowedException {
    HeaderMutationFilter filter = HeaderMutationFilter.INSTANCE.create(Optional.empty());
    HeaderMutations mutations = HeaderMutations.create(
        RequestHeaderMutations.create(
            ImmutableList.of(header("add-key", "add-value"), header(":authority", "new-authority"),
                header("host", "new-host"), header(":scheme", "https"), header(":method", "PUT")),
            ImmutableList.of("remove-key", "host", ":authority", ":scheme", ":method")),
        ResponseHeaderMutations.create(ImmutableList.of(header("resp-add-key", "resp-add-value"),
            header(":scheme", "https"))));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.requestMutations().headers())
        .containsExactly(header("add-key", "add-value"));
    assertThat(filtered.requestMutations().headersToRemove()).containsExactly("remove-key");
    assertThat(filtered.responseMutations().headers())
        .containsExactly(header("resp-add-key", "resp-add-value"));
  }

  @Test
  public void filter_cannotAppendToSystemHeaders() throws HeaderMutationDisallowedException {
    HeaderMutationFilter filter = HeaderMutationFilter.INSTANCE.create(Optional.empty());
    HeaderMutations mutations =
        HeaderMutations.create(
            RequestHeaderMutations.create(
                ImmutableList.of(
                    header("add-key", "add-value", HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD),
                    header(":authority", "new-authority",
                        HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD),
                    header("host", "new-host", HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD),
                    header(":path", "/new-path", HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD)),
                ImmutableList.of()),
            ResponseHeaderMutations.create(ImmutableList
                .of(header("host", "new-host", HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD))));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.requestMutations().headers()).containsExactly(
        header("add-key", "add-value", HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD));
    assertThat(filtered.responseMutations().headers()).isEmpty();
  }

  @Test
  public void filter_cannotRemoveSystemHeaders() throws HeaderMutationDisallowedException {
    HeaderMutationFilter filter = HeaderMutationFilter.INSTANCE.create(Optional.empty());
    HeaderMutations mutations = HeaderMutations.create(
        RequestHeaderMutations.create(ImmutableList.of(),
            ImmutableList.of("remove-key", "host", ":foo", ":bar")),
        ResponseHeaderMutations.create(ImmutableList.of()));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.requestMutations().headersToRemove()).containsExactly("remove-key");
  }

  @Test
  public void filter_canOverrideSystemHeadersNotInImmutableHeaders()
      throws HeaderMutationDisallowedException {
    HeaderMutationFilter filter = HeaderMutationFilter.INSTANCE.create(Optional.empty());
    HeaderMutations mutations = HeaderMutations.create(
        RequestHeaderMutations.create(
            ImmutableList.of(header("user-agent", "new-agent"),
                header(":path", "/new/path", HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD),
                header(":grpc-trace-bin", "binary-value", HeaderAppendAction.ADD_IF_ABSENT)),
            ImmutableList.of()),
        ResponseHeaderMutations.create(ImmutableList
            .of(header(":alt-svc", "h3=:443", HeaderAppendAction.OVERWRITE_IF_EXISTS))));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.requestMutations().headers()).containsExactly(
        header("user-agent", "new-agent"),
        header(":path", "/new/path", HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD),
        header(":grpc-trace-bin", "binary-value", HeaderAppendAction.ADD_IF_ABSENT));
    assertThat(filtered.responseMutations().headers())
        .containsExactly(header(":alt-svc", "h3=:443", HeaderAppendAction.OVERWRITE_IF_EXISTS));
  }

  @Test
  public void filter_disallowAll_disablesAllModifications()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules = HeaderMutationRulesConfig.builder().disallowAll(true).build();
    HeaderMutationFilter filter = HeaderMutationFilter.INSTANCE.create(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        RequestHeaderMutations.create(ImmutableList.of(header("add-key", "add-value")),
            ImmutableList.of("remove-key")),
        ResponseHeaderMutations.create(ImmutableList.of(header("resp-add-key", "resp-add-value"))));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.requestMutations().headers()).isEmpty();
    assertThat(filtered.requestMutations().headersToRemove()).isEmpty();
    assertThat(filtered.responseMutations().headers()).isEmpty();
  }

  @Test
  public void filter_disallowExpression_filtersRelevantExpressions()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules = HeaderMutationRulesConfig.builder()
        .disallowExpression(Pattern.compile("^x-private-.*")).build();
    HeaderMutationFilter filter = HeaderMutationFilter.INSTANCE.create(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        RequestHeaderMutations.create(
            ImmutableList.of(header("x-public", "value"), header("x-private-key", "value")),
            ImmutableList.of("x-public-remove", "x-private-remove")),
        ResponseHeaderMutations.create(
            ImmutableList.of(header("x-public-resp", "value"), header("x-private-resp", "value"))));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.requestMutations().headers()).containsExactly(header("x-public", "value"));
    assertThat(filtered.requestMutations().headersToRemove()).containsExactly("x-public-remove");
    assertThat(filtered.responseMutations().headers())
        .containsExactly(header("x-public-resp", "value"));
  }

  @Test
  public void filter_allowExpression_onlyAllowsRelevantExpressions()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules = HeaderMutationRulesConfig.builder()
        .allowExpression(Pattern.compile("^x-allowed-.*")).build();
    HeaderMutationFilter filter = HeaderMutationFilter.INSTANCE.create(Optional.of(rules));
    HeaderMutations mutations =
        HeaderMutations.create(
            RequestHeaderMutations.create(
                ImmutableList.of(header("x-allowed-key", "value"),
                    header("not-allowed-key", "value")),
                ImmutableList.of("x-allowed-remove", "not-allowed-remove")),
            ResponseHeaderMutations.create(ImmutableList.of(header("x-allowed-resp-key", "value"),
                header("not-allowed-resp-key", "value"))));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.requestMutations().headers())
        .containsExactly(header("x-allowed-key", "value"));
    assertThat(filtered.requestMutations().headersToRemove()).containsExactly("x-allowed-remove");
    assertThat(filtered.responseMutations().headers())
        .containsExactly(header("x-allowed-resp-key", "value"));
  }

  @Test
  public void filter_allowExpression_overridesDisallowAll()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules = HeaderMutationRulesConfig.builder().disallowAll(true)
        .allowExpression(Pattern.compile("^x-allowed-.*")).build();
    HeaderMutationFilter filter = HeaderMutationFilter.INSTANCE.create(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        RequestHeaderMutations.create(
            ImmutableList.of(header("x-allowed-key", "value"), header("not-allowed", "value")),
            ImmutableList.of("x-allowed-remove", "not-allowed-remove")),
        ResponseHeaderMutations.create(ImmutableList.of(header("x-allowed-resp-key", "value"),
            header("not-allowed-resp-key", "value"))));

    HeaderMutations filtered = filter.filter(mutations);

    assertThat(filtered.requestMutations().headers())
        .containsExactly(header("x-allowed-key", "value"));
    assertThat(filtered.requestMutations().headersToRemove()).containsExactly("x-allowed-remove");
    assertThat(filtered.responseMutations().headers())
        .containsExactly(header("x-allowed-resp-key", "value"));
  }

  @Test(expected = HeaderMutationDisallowedException.class)
  public void filter_disallowIsError_throwsExceptionOnDisallowed()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules =
        HeaderMutationRulesConfig.builder().disallowAll(true).disallowIsError(true).build();
    HeaderMutationFilter filter = HeaderMutationFilter.INSTANCE.create(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(RequestHeaderMutations
        .create(ImmutableList.of(header("add-key", "add-value")), ImmutableList.of()),
        ResponseHeaderMutations.create(ImmutableList.of()));
    filter.filter(mutations);
  }

  @Test(expected = HeaderMutationDisallowedException.class)
  public void filter_disallowIsError_throwsExceptionOnDisallowedRemove()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules =
        HeaderMutationRulesConfig.builder().disallowAll(true).disallowIsError(true).build();
    HeaderMutationFilter filter = HeaderMutationFilter.INSTANCE.create(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        RequestHeaderMutations.create(ImmutableList.of(), ImmutableList.of("remove-key")),
        ResponseHeaderMutations.create(ImmutableList.of()));
    filter.filter(mutations);
  }

  @Test(expected = HeaderMutationDisallowedException.class)
  public void filter_disallowIsError_throwsExceptionOnDisallowedResponseHeader()
      throws HeaderMutationDisallowedException {
    HeaderMutationRulesConfig rules =
        HeaderMutationRulesConfig.builder().disallowAll(true).disallowIsError(true).build();
    HeaderMutationFilter filter = HeaderMutationFilter.INSTANCE.create(Optional.of(rules));
    HeaderMutations mutations = HeaderMutations.create(
        RequestHeaderMutations.create(ImmutableList.of(), ImmutableList.of()),
        ResponseHeaderMutations.create(ImmutableList.of(header("resp-add-key", "resp-add-value"))));
    filter.filter(mutations);
  }
}
