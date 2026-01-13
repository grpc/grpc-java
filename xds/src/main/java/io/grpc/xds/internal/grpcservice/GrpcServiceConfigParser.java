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

package io.grpc.xds.internal.grpcservice;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.OAuth2Credentials;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.grpc_service.call_credentials.access_token.v3.AccessTokenCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.xds.v3.XdsCredentials;
import io.grpc.CallCredentials;
import io.grpc.CompositeCallCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Metadata;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import io.grpc.alts.GoogleDefaultChannelCredentials;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.xds.XdsChannelCredentials;
import io.grpc.xds.internal.XdsHeaderValidator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Parser for {@link io.envoyproxy.envoy.config.core.v3.GrpcService} and related protos.
 */
public final class GrpcServiceConfigParser {

  static final String TLS_CREDENTIALS_TYPE_URL =
      "type.googleapis.com/envoy.extensions.grpc_service.channel_credentials."
          + "tls.v3.TlsCredentials";
  static final String LOCAL_CREDENTIALS_TYPE_URL =
      "type.googleapis.com/envoy.extensions.grpc_service.channel_credentials."
          + "local.v3.LocalCredentials";
  static final String XDS_CREDENTIALS_TYPE_URL =
      "type.googleapis.com/envoy.extensions.grpc_service.channel_credentials."
          + "xds.v3.XdsCredentials";
  static final String INSECURE_CREDENTIALS_TYPE_URL =
      "type.googleapis.com/envoy.extensions.grpc_service.channel_credentials."
          + "insecure.v3.InsecureCredentials";
  static final String GOOGLE_DEFAULT_CREDENTIALS_TYPE_URL =
      "type.googleapis.com/envoy.extensions.grpc_service.channel_credentials."
          + "google_default.v3.GoogleDefaultCredentials";

  private GrpcServiceConfigParser() {}

  /**
   * Parses the {@link io.envoyproxy.envoy.config.core.v3.GrpcService} proto to create a
   * {@link GrpcServiceConfig} instance.
   *
   * @param grpcServiceProto The proto to parse.
   * @return A {@link GrpcServiceConfig} instance.
   * @throws GrpcServiceParseException if the proto is invalid or uses unsupported features.
   */
  public static GrpcServiceConfig parse(GrpcService grpcServiceProto,
      GrpcServiceXdsContextProvider contextProvider)
      throws GrpcServiceParseException {
    if (!grpcServiceProto.hasGoogleGrpc()) {
      throw new GrpcServiceParseException(
          "Unsupported: GrpcService must have GoogleGrpc, got: " + grpcServiceProto);
    }
    GrpcServiceConfig.GoogleGrpcConfig googleGrpcConfig =
        parseGoogleGrpcConfig(grpcServiceProto.getGoogleGrpc(), contextProvider);

    GrpcServiceConfig.Builder builder = GrpcServiceConfig.newBuilder().googleGrpc(googleGrpcConfig);

    ImmutableList.Builder<HeaderValue> initialMetadata = ImmutableList.builder();
    for (io.envoyproxy.envoy.config.core.v3.HeaderValue header : grpcServiceProto
        .getInitialMetadataList()) {
      String key = header.getKey();
      if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        if (!XdsHeaderValidator.isValid(key, header.getRawValue().size())) {
          throw new GrpcServiceParseException("Invalid initial metadata header: " + key);
        }
        initialMetadata.add(HeaderValue.create(key, header.getRawValue()));
      } else {
        if (!XdsHeaderValidator.isValid(key, header.getValue().length())) {
          throw new GrpcServiceParseException("Invalid initial metadata header: " + key);
        }
        initialMetadata.add(HeaderValue.create(key, header.getValue()));
      }
    }
    builder.initialMetadata(initialMetadata.build());

    if (grpcServiceProto.hasTimeout()) {
      com.google.protobuf.Duration timeout = grpcServiceProto.getTimeout();
      if (timeout.getSeconds() < 0 || timeout.getNanos() < 0
          || (timeout.getSeconds() == 0 && timeout.getNanos() == 0)) {
        throw new GrpcServiceParseException("Timeout must be strictly positive");
      }
      builder.timeout(Duration.ofSeconds(timeout.getSeconds(), timeout.getNanos()));
    }
    return builder.build();
  }

  /**
   * Parses the {@link io.envoyproxy.envoy.config.core.v3.GrpcService.GoogleGrpc} proto to create a
   * {@link GrpcServiceConfig.GoogleGrpcConfig} instance.
   *
   * @param googleGrpcProto The proto to parse.
   * @return A {@link GrpcServiceConfig.GoogleGrpcConfig} instance.
   * @throws GrpcServiceParseException if the proto is invalid.
   */
  public static GrpcServiceConfig.GoogleGrpcConfig parseGoogleGrpcConfig(
      GrpcService.GoogleGrpc googleGrpcProto, GrpcServiceXdsContextProvider contextProvider)
      throws GrpcServiceParseException {

    String targetUri = googleGrpcProto.getTargetUri();
    GrpcServiceXdsContext context = contextProvider.getContextForTarget(targetUri);

    if (!context.isTargetUriSchemeSupported()) {
      throw new GrpcServiceParseException("Target URI scheme is not resolvable: " + targetUri);
    }

    if (!context.isTrustedControlPlane()) {
      Optional<GrpcServiceXdsContext.AllowedGrpcService> override =
          context.validAllowedGrpcService();
      if (!override.isPresent()) {
        throw new GrpcServiceParseException(
            "Untrusted xDS server & URI not found in allowed_grpc_services: " + targetUri);
      }

      GrpcServiceConfig.GoogleGrpcConfig.Builder builder =
          GrpcServiceConfig.GoogleGrpcConfig.builder()
              .target(targetUri)
              .configuredChannelCredentials(override.get().configuredChannelCredentials());
      if (override.get().callCredentials().isPresent()) {
        builder.callCredentials(override.get().callCredentials().get());
      }
      return builder.build();
    }

    ConfiguredChannelCredentials channelCreds = null;
    if (googleGrpcProto.getChannelCredentialsPluginCount() > 0) {
      try {
        channelCreds = extractChannelCredentials(googleGrpcProto.getChannelCredentialsPluginList());
      } catch (GrpcServiceParseException e) {
        // Fall back to channel_credentials if plugins are not supported
      }
    }

    if (channelCreds == null) {
      throw new GrpcServiceParseException("No valid supported channel_credentials found");
    }

    Optional<CallCredentials> callCreds =
        extractCallCredentials(googleGrpcProto.getCallCredentialsPluginList());

    GrpcServiceConfig.GoogleGrpcConfig.Builder builder =
        GrpcServiceConfig.GoogleGrpcConfig.builder().target(googleGrpcProto.getTargetUri())
            .configuredChannelCredentials(channelCreds);
    if (callCreds.isPresent()) {
      builder.callCredentials(callCreds.get());
    }
    return builder.build();
  }

  private static Optional<ConfiguredChannelCredentials> channelCredsFromProto(
      Any cred) throws GrpcServiceParseException {
    String typeUrl = cred.getTypeUrl();
    try {
      switch (typeUrl) {
        case GOOGLE_DEFAULT_CREDENTIALS_TYPE_URL:
          return Optional.of(ConfiguredChannelCredentials.create(
              GoogleDefaultChannelCredentials.create(),
              new ProtoChannelCredsConfig(typeUrl, cred)));
        case INSECURE_CREDENTIALS_TYPE_URL:
          return Optional.of(ConfiguredChannelCredentials.create(
              InsecureChannelCredentials.create(),
              new ProtoChannelCredsConfig(typeUrl, cred)));
        case XDS_CREDENTIALS_TYPE_URL:
          XdsCredentials xdsConfig = cred.unpack(XdsCredentials.class);
          Optional<ConfiguredChannelCredentials> fallbackCreds =
              channelCredsFromProto(xdsConfig.getFallbackCredentials());
          if (!fallbackCreds.isPresent()) {
            throw new GrpcServiceParseException(
                "Unsupported fallback credentials type for XdsCredentials");
          }
          return Optional.of(ConfiguredChannelCredentials.create(
              XdsChannelCredentials.create(fallbackCreds.get().channelCredentials()),
              new ProtoChannelCredsConfig(typeUrl, cred)));
        case LOCAL_CREDENTIALS_TYPE_URL:
          throw new UnsupportedOperationException(
              "LocalCredentials are not supported in grpc-java. "
                  + "See https://github.com/grpc/grpc-java/issues/8928");
        case TLS_CREDENTIALS_TYPE_URL:
          // For this PR, we establish this structural skeleton,
          // but throw an UnsupportedOperationException until the exact stream conversions are
          // merged.
          throw new UnsupportedOperationException(
              "TlsCredentials input stream construction pending.");
        default:
          return Optional.empty();
      }
    } catch (InvalidProtocolBufferException e) {
      throw new GrpcServiceParseException("Failed to parse channel credentials: " + e.getMessage());
    }
  }

  private static ConfiguredChannelCredentials extractChannelCredentials(
      List<Any> channelCredentialPlugins) throws GrpcServiceParseException {
    for (Any cred : channelCredentialPlugins) {
      Optional<ConfiguredChannelCredentials> parsed = channelCredsFromProto(cred);
      if (parsed.isPresent()) {
        return parsed.get();
      }
    }
    throw new GrpcServiceParseException("No valid supported channel_credentials found");
  }

  private static Optional<CallCredentials> callCredsFromProto(Any cred)
      throws GrpcServiceParseException {
    if (cred.is(AccessTokenCredentials.class)) {
      try {
        AccessTokenCredentials accessToken = cred.unpack(AccessTokenCredentials.class);
        if (accessToken.getToken().isEmpty()) {
          throw new GrpcServiceParseException("Missing or empty access token in call credentials.");
        }
        return Optional
            .of(new SecurityAwareAccessTokenCredentials(MoreCallCredentials.from(OAuth2Credentials
                .create(new AccessToken(accessToken.getToken(), new Date(Long.MAX_VALUE))))));
      } catch (InvalidProtocolBufferException e) {
        throw new GrpcServiceParseException(
            "Failed to parse access token credentials: " + e.getMessage());
      }
    }
    return Optional.empty();
  }

  private static Optional<CallCredentials> extractCallCredentials(List<Any> callCredentialPlugins)
      throws GrpcServiceParseException {
    List<CallCredentials> creds = new ArrayList<>();
    for (Any cred : callCredentialPlugins) {
      Optional<CallCredentials> parsed = callCredsFromProto(cred);
      if (parsed.isPresent()) {
        creds.add(parsed.get());
      }
    }
    return creds.stream().reduce(CompositeCallCredentials::new);
  }

  private static final class SecurityAwareAccessTokenCredentials extends CallCredentials {

    private final CallCredentials delegate;

    SecurityAwareAccessTokenCredentials(CallCredentials delegate) {
      this.delegate = delegate;
    }

    @Override
    public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor,
        MetadataApplier applier) {
      if (requestInfo.getSecurityLevel() != SecurityLevel.PRIVACY_AND_INTEGRITY) {
        applier.fail(Status.UNAUTHENTICATED.withDescription(
            "OAuth2 credentials require connection with PRIVACY_AND_INTEGRITY security level"));
        return;
      }
      delegate.applyRequestMetadata(requestInfo, appExecutor, applier);
    }
  }



  static final class ProtoChannelCredsConfig implements ChannelCredsConfig {
    private final String type;
    private final Any configProto;

    ProtoChannelCredsConfig(String type, Any configProto) {
      this.type = type;
      this.configProto = configProto;
    }

    @Override
    public String type() {
      return type;
    }

    Any configProto() {
      return configProto;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ProtoChannelCredsConfig that = (ProtoChannelCredsConfig) o;
      return java.util.Objects.equals(type, that.type)
          && java.util.Objects.equals(configProto, that.configProto);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(type, configProto);
    }
  }



}
