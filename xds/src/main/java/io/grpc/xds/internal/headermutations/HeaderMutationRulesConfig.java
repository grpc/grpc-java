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

import com.google.auto.value.AutoValue;
import io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Represents the configuration for header mutation rules, as defined in the
 * {@link io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules} proto.
 */
@AutoValue
public abstract class HeaderMutationRulesConfig {
  /** Creates a new builder for creating {@link HeaderMutationRulesConfig} instances. */
  public static Builder builder() {
    return new AutoValue_HeaderMutationRulesConfig.Builder().disallowAll(false)
        .disallowIsError(false);
  }

  /**
   * If set, allows any header that matches this regular expression.
   *
   * @see HeaderMutationRules#getAllowExpression()
   */
  public abstract Optional<Pattern> allowExpression();

  /**
   * If set, disallows any header that matches this regular expression.
   *
   * @see HeaderMutationRules#getDisallowExpression()
   */
  public abstract Optional<Pattern> disallowExpression();

  /**
   * If true, disallows all header mutations.
   *
   * @see HeaderMutationRules#getDisallowAll()
   */
  public abstract boolean disallowAll();

  /**
   * If true, disallows any header mutation that would result in an invalid header value.
   *
   * @see HeaderMutationRules#getDisallowIsError()
   */
  public abstract boolean disallowIsError();


  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder allowExpression(Pattern matcher);

    public abstract Builder disallowExpression(Pattern matcher);

    public abstract Builder disallowAll(boolean disallowAll);

    public abstract Builder disallowIsError(boolean disallowIsError);

    public abstract HeaderMutationRulesConfig build();
  }
}
