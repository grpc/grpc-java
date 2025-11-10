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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.RuntimeFeatureFlag;
import io.envoyproxy.envoy.config.core.v3.RuntimeFractionalPercent;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.envoyproxy.envoy.extensions.grpc_service.call_credentials.access_token.v3.AccessTokenCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.google_default.v3.GoogleDefaultCredentials;
import io.envoyproxy.envoy.type.matcher.v3.ListStringMatcher;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.FractionalPercent.DenominatorType;
import io.grpc.Status;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesConfig;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ExtAuthzConfigTest {

  private static final Any GOOGLE_DEFAULT_CHANNEL_CREDS =
      Any.pack(GoogleDefaultCredentials.newBuilder().build());
  private static final Any FAKE_ACCESS_TOKEN_CALL_CREDS =
      Any.pack(AccessTokenCredentials.newBuilder().build());

  private ExtAuthz.Builder extAuthzBuilder;

  @Before
  public void setUp() {
    extAuthzBuilder = ExtAuthz.newBuilder()
        .setGrpcService(io.envoyproxy.envoy.config.core.v3.GrpcService.newBuilder()
            .setGoogleGrpc(io.envoyproxy.envoy.config.core.v3.GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("test-cluster")
                .addChannelCredentialsPlugin(GOOGLE_DEFAULT_CHANNEL_CREDS)
                .addCallCredentialsPlugin(FAKE_ACCESS_TOKEN_CALL_CREDS).build())
            .build());
  }

  @Test
  public void fromProto_missingGrpcService_throws() {
    ExtAuthz extAuthz = ExtAuthz.newBuilder().build();
    try {
      ExtAuthzConfig.fromProto(extAuthz);
      fail("Expected ExtAuthzParseException");
    } catch (ExtAuthzParseException e) {
      assertThat(e).hasMessageThat()
          .isEqualTo("unsupported ExtAuthz service type: only grpc_service is supported");
    }
  }

  @Test
  public void fromProto_invalidGrpcService_throws() {
    ExtAuthz extAuthz = ExtAuthz.newBuilder()
        .setGrpcService(io.envoyproxy.envoy.config.core.v3.GrpcService.newBuilder().build())
        .build();
    try {
      ExtAuthzConfig.fromProto(extAuthz);
      fail("Expected ExtAuthzParseException");
    } catch (ExtAuthzParseException e) {
      assertThat(e).hasMessageThat().startsWith("Failed to parse GrpcService config:");
    }
  }

  @Test
  public void fromProto_invalidAllowExpression_throws() {
    ExtAuthz extAuthz = extAuthzBuilder
        .setDecoderHeaderMutationRules(HeaderMutationRules.newBuilder()
            .setAllowExpression(RegexMatcher.newBuilder().setRegex("[invalid").build()).build())
        .build();
    try {
      ExtAuthzConfig.fromProto(extAuthz);
      fail("Expected ExtAuthzParseException");
    } catch (ExtAuthzParseException e) {
      assertThat(e).hasMessageThat().startsWith("Invalid regex pattern for allow_expression:");
    }
  }

  @Test
  public void fromProto_invalidDisallowExpression_throws() {
    ExtAuthz extAuthz = extAuthzBuilder
        .setDecoderHeaderMutationRules(HeaderMutationRules.newBuilder()
            .setDisallowExpression(RegexMatcher.newBuilder().setRegex("[invalid").build()).build())
        .build();
    try {
      ExtAuthzConfig.fromProto(extAuthz);
      fail("Expected ExtAuthzParseException");
    } catch (ExtAuthzParseException e) {
      assertThat(e).hasMessageThat().startsWith("Invalid regex pattern for disallow_expression:");
    }
  }

  @Test
  public void fromProto_success() throws ExtAuthzParseException {
    ExtAuthz extAuthz = extAuthzBuilder
        .setGrpcService(extAuthzBuilder.getGrpcServiceBuilder()
            .setTimeout(com.google.protobuf.Duration.newBuilder().setSeconds(5).build())
            .addInitialMetadata(HeaderValue.newBuilder().setKey("key").setValue("value").build())
            .build())
        .setFailureModeAllow(true).setFailureModeAllowHeaderAdd(true)
        .setIncludePeerCertificate(true)
        .setStatusOnError(
            io.envoyproxy.envoy.type.v3.HttpStatus.newBuilder().setCodeValue(403).build())
        .setDenyAtDisable(
            RuntimeFeatureFlag.newBuilder().setDefaultValue(BoolValue.of(true)).build())
        .setFilterEnabled(RuntimeFractionalPercent.newBuilder()
            .setDefaultValue(FractionalPercent.newBuilder().setNumerator(50)
                .setDenominator(DenominatorType.TEN_THOUSAND).build())
            .build())
        .setAllowedHeaders(ListStringMatcher.newBuilder()
            .addPatterns(StringMatcher.newBuilder().setExact("allowed-header").build()).build())
        .setDisallowedHeaders(ListStringMatcher.newBuilder()
            .addPatterns(StringMatcher.newBuilder().setPrefix("disallowed-").build()).build())
        .setDecoderHeaderMutationRules(HeaderMutationRules.newBuilder()
            .setAllowExpression(RegexMatcher.newBuilder().setRegex("allow.*").build())
            .setDisallowExpression(RegexMatcher.newBuilder().setRegex("disallow.*").build())
            .setDisallowAll(BoolValue.of(true)).setDisallowIsError(BoolValue.of(true)).build())
        .build();

    ExtAuthzConfig config = ExtAuthzConfig.fromProto(extAuthz);

    assertThat(config.grpcService().googleGrpc().target()).isEqualTo("test-cluster");
    assertThat(config.grpcService().timeout().get().getSeconds()).isEqualTo(5);
    assertThat(config.grpcService().initialMetadata().isPresent()).isTrue();
    assertThat(config.failureModeAllow()).isTrue();
    assertThat(config.failureModeAllowHeaderAdd()).isTrue();
    assertThat(config.includePeerCertificate()).isTrue();
    assertThat(config.statusOnError().getCode()).isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(config.statusOnError().getDescription()).isEqualTo("HTTP status code 403");
    assertThat(config.denyAtDisable()).isTrue();
    assertThat(config.filterEnabled()).isEqualTo(Matchers.FractionMatcher.create(50, 10_000));
    assertThat(config.allowedHeaders()).hasSize(1);
    assertThat(config.allowedHeaders().get(0).matches("allowed-header")).isTrue();
    assertThat(config.disallowedHeaders()).hasSize(1);
    assertThat(config.disallowedHeaders().get(0).matches("disallowed-foo")).isTrue();
    assertThat(config.decoderHeaderMutationRules().isPresent()).isTrue();
    HeaderMutationRulesConfig rules = config.decoderHeaderMutationRules().get();
    assertThat(rules.allowExpression().get().pattern()).isEqualTo("allow.*");
    assertThat(rules.disallowExpression().get().pattern()).isEqualTo("disallow.*");
    assertThat(rules.disallowAll()).isTrue();
    assertThat(rules.disallowIsError()).isTrue();
  }

  @Test
  public void fromProto_saneDefaults() throws ExtAuthzParseException {
    ExtAuthz extAuthz = extAuthzBuilder.build();

    ExtAuthzConfig config = ExtAuthzConfig.fromProto(extAuthz);

    assertThat(config.failureModeAllow()).isFalse();
    assertThat(config.failureModeAllowHeaderAdd()).isFalse();
    assertThat(config.includePeerCertificate()).isFalse();
    assertThat(config.statusOnError()).isEqualTo(Status.PERMISSION_DENIED);
    assertThat(config.denyAtDisable()).isFalse();
    assertThat(config.filterEnabled()).isEqualTo(Matchers.FractionMatcher.create(100, 100));
    assertThat(config.allowedHeaders()).isEmpty();
    assertThat(config.disallowedHeaders()).isEmpty();
    assertThat(config.decoderHeaderMutationRules().isPresent()).isFalse();
  }

  @Test
  public void fromProto_headerMutationRules_allowExpressionOnly() throws ExtAuthzParseException {
    ExtAuthz extAuthz = extAuthzBuilder
        .setDecoderHeaderMutationRules(HeaderMutationRules.newBuilder()
            .setAllowExpression(RegexMatcher.newBuilder().setRegex("allow.*").build()).build())
        .build();

    ExtAuthzConfig config = ExtAuthzConfig.fromProto(extAuthz);

    assertThat(config.decoderHeaderMutationRules().isPresent()).isTrue();
    HeaderMutationRulesConfig rules = config.decoderHeaderMutationRules().get();
    assertThat(rules.allowExpression().get().pattern()).isEqualTo("allow.*");
    assertThat(rules.disallowExpression().isPresent()).isFalse();
  }

  @Test
  public void fromProto_headerMutationRules_disallowExpressionOnly() throws ExtAuthzParseException {
    ExtAuthz extAuthz = extAuthzBuilder
        .setDecoderHeaderMutationRules(HeaderMutationRules.newBuilder()
            .setDisallowExpression(RegexMatcher.newBuilder().setRegex("disallow.*").build())
            .build())
        .build();

    ExtAuthzConfig config = ExtAuthzConfig.fromProto(extAuthz);

    assertThat(config.decoderHeaderMutationRules().isPresent()).isTrue();
    HeaderMutationRulesConfig rules = config.decoderHeaderMutationRules().get();
    assertThat(rules.allowExpression().isPresent()).isFalse();
    assertThat(rules.disallowExpression().get().pattern()).isEqualTo("disallow.*");
  }

  @Test
  public void fromProto_filterEnabled_hundred() throws ExtAuthzParseException {
    ExtAuthz extAuthz = extAuthzBuilder
        .setFilterEnabled(RuntimeFractionalPercent.newBuilder().setDefaultValue(FractionalPercent
            .newBuilder().setNumerator(25).setDenominator(DenominatorType.HUNDRED).build()).build())
        .build();

    ExtAuthzConfig config = ExtAuthzConfig.fromProto(extAuthz);

    assertThat(config.filterEnabled()).isEqualTo(Matchers.FractionMatcher.create(25, 100));
  }

  @Test
  public void fromProto_filterEnabled_million() throws ExtAuthzParseException {
    ExtAuthz extAuthz = extAuthzBuilder
        .setFilterEnabled(
            RuntimeFractionalPercent.newBuilder().setDefaultValue(FractionalPercent.newBuilder()
                .setNumerator(123456).setDenominator(DenominatorType.MILLION).build()).build())
        .build();

    ExtAuthzConfig config = ExtAuthzConfig.fromProto(extAuthz);

    assertThat(config.filterEnabled())
        .isEqualTo(Matchers.FractionMatcher.create(123456, 1_000_000));
  }

  @Test
  public void fromProto_filterEnabled_unrecognizedDenominator() {
    ExtAuthz extAuthz = extAuthzBuilder
        .setFilterEnabled(RuntimeFractionalPercent.newBuilder()
            .setDefaultValue(
                FractionalPercent.newBuilder().setNumerator(1).setDenominatorValue(4).build())
            .build())
        .build();

    try {
      ExtAuthzConfig.fromProto(extAuthz);
      fail("Expected ExtAuthzParseException");
    } catch (ExtAuthzParseException e) {
      assertThat(e).hasMessageThat().isEqualTo("Unknown denominator type: UNRECOGNIZED");
    }
  }
}