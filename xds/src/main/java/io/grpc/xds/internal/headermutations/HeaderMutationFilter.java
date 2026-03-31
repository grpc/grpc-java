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

import com.google.common.collect.ImmutableList;
import io.grpc.xds.internal.grpcservice.HeaderValueValidationUtils;
import io.grpc.xds.internal.headermutations.HeaderMutations.RequestHeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutations.ResponseHeaderMutations;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The HeaderMutationFilter class is responsible for filtering header mutations based on a given set
 * of rules.
 */
public class HeaderMutationFilter {
  private final Optional<HeaderMutationRulesConfig> mutationRules;



  public HeaderMutationFilter(Optional<HeaderMutationRulesConfig> mutationRules) { // NOPMD
    this.mutationRules = mutationRules;
  }

  /**
   * Filters the given header mutations based on the configured rules and returns the allowed
   * mutations.
   *
   * @param mutations The header mutations to filter
   * @return The allowed header mutations.
   * @throws HeaderMutationDisallowedException if a disallowed mutation is encountered and the rules
   *         specify that this should be an error.
   */
  public HeaderMutations filter(HeaderMutations mutations)
      throws HeaderMutationDisallowedException {
    ImmutableList<HeaderValueOption> allowedRequestHeaders =
        filterCollection(mutations.requestMutations().headers(),
            this::shouldIgnore, this::isHeaderMutationAllowed);
    ImmutableList<String> allowedRequestHeadersToRemove =
        filterCollection(mutations.requestMutations().headersToRemove(),
            this::shouldIgnore, this::isHeaderMutationAllowed);
    ImmutableList<HeaderValueOption> allowedResponseHeaders =
        filterCollection(mutations.responseMutations().headers(),
            this::shouldIgnore, this::isHeaderMutationAllowed);
    return HeaderMutations.create(
        RequestHeaderMutations.create(allowedRequestHeaders, allowedRequestHeadersToRemove),
        ResponseHeaderMutations.create(allowedResponseHeaders));
  }

  /**
   * A generic helper to filter a collection based on a predicate.
   */
  private <T> ImmutableList<T> filterCollection(Collection<T> items,
      Predicate<T> isIgnoredPredicate, Predicate<T> isAllowedPredicate)
      throws HeaderMutationDisallowedException {
    ImmutableList.Builder<T> allowed = ImmutableList.builder();
    for (T item : items) {
      if (isIgnoredPredicate.test(item)) {
        continue;
      }
      if (isAllowedPredicate.test(item)) {
        allowed.add(item);
      } else if (disallowIsError()) {
        throw new HeaderMutationDisallowedException(
            "Header mutation disallowed for header: " + item);
      }
    }
    return allowed.build();
  }

  private boolean shouldIgnore(String key) {
    return HeaderValueValidationUtils.shouldIgnore(key);
  }

  private boolean shouldIgnore(HeaderValueOption option) {
    return HeaderValueValidationUtils.shouldIgnore(option.header());
  }

  private boolean isHeaderMutationAllowed(HeaderValueOption option) {
    return isHeaderMutationAllowed(option.header().key());
  }

  private boolean isHeaderMutationAllowed(String headerName) {
    return mutationRules.map(rules -> isHeaderMutationAllowed(headerName, rules))
        .orElse(true);
  }

  private boolean isHeaderMutationAllowed(String lowerCaseHeaderName,
      HeaderMutationRulesConfig rules) {
    if (rules.disallowExpression().isPresent()
        && rules.disallowExpression().get().matcher(lowerCaseHeaderName).matches()) {
      return false;
    }
    if (rules.allowExpression().isPresent()) {
      return rules.allowExpression().get().matcher(lowerCaseHeaderName).matches();
    }
    return !rules.disallowAll();
  }

  private boolean disallowIsError() {
    return mutationRules.map(HeaderMutationRulesConfig::disallowIsError).orElse(false);
  }
}
