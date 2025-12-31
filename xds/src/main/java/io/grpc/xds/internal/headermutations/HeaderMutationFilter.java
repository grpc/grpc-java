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
import com.google.common.collect.ImmutableSet;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.grpc.xds.internal.headermutations.HeaderMutations.RequestHeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutations.ResponseHeaderMutations;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The HeaderMutationFilter class is responsible for filtering header mutations based on a given set
 * of rules.
 */
public interface HeaderMutationFilter {

  /**
   * A factory for creating {@link HeaderMutationFilter} instances.
   */
  @FunctionalInterface
  interface Factory {
    /**
     * Creates a new instance of {@code HeaderMutationFilter}.
     *
     * @param mutationRules The rules for header mutations. If an empty {@code Optional} is
     *        provided, all header mutations are allowed by default, except for certain system
     *        headers. If a {@link HeaderMutationRulesConfig} is provided, mutations will be
     *        filtered based on the specified rules.
     */
    HeaderMutationFilter create(Optional<HeaderMutationRulesConfig> mutationRules);
  }

  /**
   * The default factory for creating {@link HeaderMutationFilter} instances.
   */
  Factory INSTANCE = HeaderMutationFilterImpl::new;

  /**
   * Filters the given header mutations based on the configured rules and returns the allowed
   * mutations.
   *
   * @param mutations The header mutations to filter
   * @return The allowed header mutations.
   * @throws HeaderMutationDisallowedException if a disallowed mutation is encountered and the rules
   *         specify that this should be an error.
   */
  HeaderMutations filter(HeaderMutations mutations) throws HeaderMutationDisallowedException;

  /** Default implementation of {@link HeaderMutationFilter}. */
  final class HeaderMutationFilterImpl implements HeaderMutationFilter {
    private final Optional<HeaderMutationRulesConfig> mutationRules;

    /**
     * Set of HTTP/2 pseudo-headers and the host header that are critical for routing and protocol
     * correctness. These headers cannot be mutated by user configuration.
     */
    private static final ImmutableSet<String> IMMUTABLE_HEADERS =
        ImmutableSet.of("host", ":authority", ":scheme", ":method");

    private HeaderMutationFilterImpl(Optional<HeaderMutationRulesConfig> mutationRules) { // NOPMD
      this.mutationRules = mutationRules;
    }

    @Override
    public HeaderMutations filter(HeaderMutations mutations)
        throws HeaderMutationDisallowedException {
      ImmutableList<HeaderValueOption> allowedRequestHeaders =
          filterCollection(mutations.requestMutations().headers(),
              header -> isHeaderMutationAllowed(header.getHeader().getKey())
                  && !appendsSystemHeader(header));
      ImmutableList<String> allowedRequestHeadersToRemove =
          filterCollection(mutations.requestMutations().headersToRemove(),
              header -> isHeaderMutationAllowed(header) && isHeaderRemovalAllowed(header));
      ImmutableList<HeaderValueOption> allowedResponseHeaders =
          filterCollection(mutations.responseMutations().headers(),
              header -> isHeaderMutationAllowed(header.getHeader().getKey())
                  && !appendsSystemHeader(header));
      return HeaderMutations.create(
          RequestHeaderMutations.create(allowedRequestHeaders, allowedRequestHeadersToRemove),
          ResponseHeaderMutations.create(allowedResponseHeaders));
    }

    /**
     * A generic helper to filter a collection based on a predicate.
     *
     * @param items The collection of items to filter.
     * @param isAllowedPredicate The predicate to apply to each item.
     * @param <T> The type of items in the collection.
     * @return An immutable list of allowed items.
     * @throws HeaderMutationDisallowedException if an item is disallowed and disallowIsError is
     *         true.
     */
    private <T> ImmutableList<T> filterCollection(Collection<T> items,
        Predicate<T> isAllowedPredicate) throws HeaderMutationDisallowedException {
      ImmutableList.Builder<T> allowed = ImmutableList.builder();
      for (T item : items) {
        if (isAllowedPredicate.test(item)) {
          allowed.add(item);
        } else if (disallowIsError()) {
          throw new HeaderMutationDisallowedException(
              "Header mutation disallowed for header: " + item);
        }
      }
      return allowed.build();
    }

    private boolean isHeaderRemovalAllowed(String headerKey) {
      return !isSystemHeaderKey(headerKey);
    }

    private boolean appendsSystemHeader(HeaderValueOption headerValueOption) {
      String key = headerValueOption.getHeader().getKey();
      boolean isAppend = headerValueOption
          .getAppendAction() == HeaderValueOption.HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD;
      return isAppend && isSystemHeaderKey(key);
    }

    private boolean isSystemHeaderKey(String key) {
      return key.startsWith(":") || key.toLowerCase(Locale.ROOT).equals("host");
    }

    private boolean isHeaderMutationAllowed(String headerName) {
      String lowerCaseHeaderName = headerName.toLowerCase(Locale.ROOT);
      if (IMMUTABLE_HEADERS.contains(lowerCaseHeaderName)) {
        return false;
      }
      return mutationRules.map(rules -> isHeaderMutationAllowed(lowerCaseHeaderName, rules))
          .orElse(true);
    }

    private boolean isHeaderMutationAllowed(String lowerCaseHeaderName,
        HeaderMutationRulesConfig rules) {
      // TODO(sauravzg): The priority is slightly unclear in the spec.
      // Both `disallowAll` and `disallow_expression` take precedence over `all other
      // settings`.
      // `allow_expression` takes precedence over everything except `disallow_expression`.
      // This is a conflict between ordering for `allow_expression` and `disallowAll`.
      // Choosing to proceed with current envoy implementation which favors `allow_expression` over
      // `disallowAll`.
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
}
