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

package io.grpc.xds.internal.extauthz;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import io.grpc.Status;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesConfig;
import java.util.Optional;

/**
 * Represents the configuration for the external authorization (ext_authz) filter. This class
 * encapsulates the settings defined in the
 * {@link io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz} proto, providing a
 * structured, immutable representation for use within gRPC. It includes configurations for the gRPC
 * service used for authorization, header mutation rules, and other filter behaviors.
 */
@AutoValue
public abstract class ExtAuthzConfig {

  /** Creates a new builder for creating {@link ExtAuthzConfig} instances. */
  public static Builder builder() {
    return new AutoValue_ExtAuthzConfig.Builder().allowedHeaders(ImmutableList.of())
        .disallowedHeaders(ImmutableList.of()).statusOnError(Status.PERMISSION_DENIED)
        .filterEnabled(Matchers.FractionMatcher.create(100, 100));
  }

  /**
   * The gRPC service configuration for the external authorization service. This is a required
   * field.
   *
   * @see ExtAuthz#getGrpcService()
   */
  public abstract GrpcServiceConfig grpcService();

  /**
   * Changes the filter's behavior on errors from the authorization service. If {@code true}, the
   * filter will accept the request even if the authorization service fails or returns an error.
   *
   * @see ExtAuthz#getFailureModeAllow()
   */
  public abstract boolean failureModeAllow();

  /**
   * Determines if the {@code x-envoy-auth-failure-mode-allowed} header is added to the request when
   * {@link #failureModeAllow()} is true.
   *
   * @see ExtAuthz#getFailureModeAllowHeaderAdd()
   */
  public abstract boolean failureModeAllowHeaderAdd();

  /**
   * Specifies if the peer certificate is sent to the external authorization service.
   *
   * @see ExtAuthz#getIncludePeerCertificate()
   */
  public abstract boolean includePeerCertificate();

  /**
   * The gRPC status returned to the client when the authorization server returns an error or is
   * unreachable. Defaults to {@code PERMISSION_DENIED}.
   *
   * @see io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz#getStatusOnError()
   */
  public abstract Status statusOnError();

  /**
   * Specifies whether to deny requests when the filter is disabled. Defaults to {@code false}.
   *
   * @see ExtAuthz#getDenyAtDisable()
   */
  public abstract boolean denyAtDisable();

  /**
   * The fraction of requests that will be checked by the authorization service. Defaults to all
   * requests.
   *
   * @see ExtAuthz#getFilterEnabled()
   */
  public abstract Matchers.FractionMatcher filterEnabled();

  /**
   * Specifies which request headers are sent to the authorization service. If empty, all headers
   * are sent.
   *
   * @see ExtAuthz#getAllowedHeaders()
   */
  public abstract ImmutableList<Matchers.StringMatcher> allowedHeaders();

  /**
   * Specifies which request headers are not sent to the authorization service. This overrides
   * {@link #allowedHeaders()}.
   *
   * @see ExtAuthz#getDisallowedHeaders()
   */
  public abstract ImmutableList<Matchers.StringMatcher> disallowedHeaders();

  /**
   * Rules for what modifications an ext_authz server may make to request headers.
   *
   * @see ExtAuthz#getDecoderHeaderMutationRules()
   */
  public abstract Optional<HeaderMutationRulesConfig> decoderHeaderMutationRules();

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder grpcService(GrpcServiceConfig grpcService);

    public abstract Builder failureModeAllow(boolean failureModeAllow);

    public abstract Builder failureModeAllowHeaderAdd(boolean failureModeAllowHeaderAdd);

    public abstract Builder includePeerCertificate(boolean includePeerCertificate);

    public abstract Builder statusOnError(Status statusOnError);

    public abstract Builder denyAtDisable(boolean denyAtDisable);

    public abstract Builder filterEnabled(Matchers.FractionMatcher filterEnabled);

    public abstract Builder allowedHeaders(Iterable<Matchers.StringMatcher> allowedHeaders);

    public abstract Builder disallowedHeaders(Iterable<Matchers.StringMatcher> disallowedHeaders);

    public abstract Builder decoderHeaderMutationRules(HeaderMutationRulesConfig rules);

    public abstract ExtAuthzConfig build();
  }
}
