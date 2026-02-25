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
import com.google.auto.value.AutoValue;
import com.google.common.io.BaseEncoding;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.grpc_service.call_credentials.access_token.v3.AccessTokenCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.xds.v3.XdsCredentials;
import io.grpc.CallCredentials;
import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Metadata;
import io.grpc.alts.GoogleDefaultChannelCredentials;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.xds.XdsChannelCredentials;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;


/**
 * A Java representation of the {@link io.envoyproxy.envoy.config.core.v3.GrpcService} proto,
 * designed for parsing and internal use within gRPC. This class encapsulates the configuration for
 * a gRPC service, including target URI, credentials, and other settings. The parsing logic adheres
 * to the specifications outlined in <a href= "https://github.com/grpc/proposal/pull/510/files">
 * A102: xDS GrpcService Support</a>. This class is immutable and uses the AutoValue library for its
 * implementation.
 */
@AutoValue
public abstract class GrpcServiceConfig {

  public static Builder builder() {
    return new AutoValue_GrpcServiceConfig.Builder();
  }

  /**
   * Parses the {@link io.envoyproxy.envoy.config.core.v3.GrpcService} proto to create a
   * {@link GrpcServiceConfig} instance. This method adheres to gRFC A102, which specifies that only
   * the {@code google_grpc} target specifier is supported. Other fields like {@code timeout} and
   * {@code initial_metadata} are also parsed as per the gRFC.
   *
   * @param grpcServiceProto The proto to parse.
   * @return A {@link GrpcServiceConfig} instance.
   * @throws GrpcServiceParseException if the proto is invalid or uses unsupported features.
   */
  public static GrpcServiceConfig fromProto(GrpcService grpcServiceProto)
      throws GrpcServiceParseException {
    if (!grpcServiceProto.hasGoogleGrpc()) {
      throw new GrpcServiceParseException(
          "Unsupported: GrpcService must have GoogleGrpc, got: " + grpcServiceProto);
    }
    GoogleGrpcConfig googleGrpcConfig =
        GoogleGrpcConfig.fromProto(grpcServiceProto.getGoogleGrpc());

    Builder builder = GrpcServiceConfig.builder().googleGrpc(googleGrpcConfig);

    if (!grpcServiceProto.getInitialMetadataList().isEmpty()) {
      Metadata initialMetadata = new Metadata();
      for (io.envoyproxy.envoy.config.core.v3.HeaderValue header : grpcServiceProto
          .getInitialMetadataList()) {
        String key = header.getKey();
        if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
          initialMetadata.put(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER),
              BaseEncoding.base64().decode(header.getValue()));
        } else {
          initialMetadata.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER),
              header.getValue());
        }
      }
      builder.initialMetadata(initialMetadata);
    }

    if (grpcServiceProto.hasTimeout()) {
      com.google.protobuf.Duration timeout = grpcServiceProto.getTimeout();
      builder.timeout(Duration.ofSeconds(timeout.getSeconds(), timeout.getNanos()));
    }
    return builder.build();
  }

  public abstract GoogleGrpcConfig googleGrpc();

  public abstract Optional<Duration> timeout();

  public abstract Optional<Metadata> initialMetadata();

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder googleGrpc(GoogleGrpcConfig googleGrpc);

    public abstract Builder timeout(Duration timeout);

    public abstract Builder initialMetadata(Metadata initialMetadata);

    public abstract GrpcServiceConfig build();
  }

  /**
   * Represents the configuration for a Google gRPC service, as defined in the
   * {@link io.envoyproxy.envoy.config.core.v3.GrpcService.GoogleGrpc} proto. This class
   * encapsulates settings specific to Google's gRPC implementation, such as target URI and
   * credentials. The parsing of this configuration is guided by gRFC A102, which specifies how gRPC
   * clients should interpret the GrpcService proto.
   */
  @AutoValue
  public abstract static class GoogleGrpcConfig {

    private static final String TLS_CREDENTIALS_TYPE_URL =
        "type.googleapis.com/envoy.extensions.grpc_service.channel_credentials."
            + "tls.v3.TlsCredentials";
    private static final String LOCAL_CREDENTIALS_TYPE_URL =
        "type.googleapis.com/envoy.extensions.grpc_service.channel_credentials."
            + "local.v3.LocalCredentials";
    private static final String XDS_CREDENTIALS_TYPE_URL =
        "type.googleapis.com/envoy.extensions.grpc_service.channel_credentials."
            + "xds.v3.XdsCredentials";
    private static final String INSECURE_CREDENTIALS_TYPE_URL =
        "type.googleapis.com/envoy.extensions.grpc_service.channel_credentials."
            + "insecure.v3.InsecureCredentials";
    private static final String GOOGLE_DEFAULT_CREDENTIALS_TYPE_URL =
        "type.googleapis.com/envoy.extensions.grpc_service.channel_credentials."
            + "google_default.v3.GoogleDefaultCredentials";

    public static Builder builder() {
      return new AutoValue_GrpcServiceConfig_GoogleGrpcConfig.Builder();
    }

    /**
     * Parses the {@link io.envoyproxy.envoy.config.core.v3.GrpcService.GoogleGrpc} proto to create
     * a {@link GoogleGrpcConfig} instance.
     *
     * @param googleGrpcProto The proto to parse.
     * @return A {@link GoogleGrpcConfig} instance.
     * @throws GrpcServiceParseException if the proto is invalid.
     */
    public static GoogleGrpcConfig fromProto(GrpcService.GoogleGrpc googleGrpcProto)
        throws GrpcServiceParseException {

      HashedChannelCredentials channelCreds =
          extractChannelCredentials(googleGrpcProto.getChannelCredentialsPluginList());

      CallCredentials callCreds =
          extractCallCredentials(googleGrpcProto.getCallCredentialsPluginList());

      return GoogleGrpcConfig.builder().target(googleGrpcProto.getTargetUri())
          .hashedChannelCredentials(channelCreds).callCredentials(callCreds).build();
    }

    public abstract String target();

    public abstract HashedChannelCredentials hashedChannelCredentials();

    public abstract CallCredentials callCredentials();

    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder target(String target);

      public abstract Builder hashedChannelCredentials(HashedChannelCredentials channelCredentials);

      public abstract Builder callCredentials(CallCredentials callCredentials);

      public abstract GoogleGrpcConfig build();
    }

    private static <T, U> T getFirstSupported(List<U> configs, Parser<T, U> parser,
        String configName) throws GrpcServiceParseException {
      List<String> errors = new ArrayList<>();
      for (U config : configs) {
        try {
          return parser.parse(config);
        } catch (GrpcServiceParseException e) {
          errors.add(e.getMessage());
        }
      }
      throw new GrpcServiceParseException(
          "No valid supported " + configName + " found. Errors: " + errors);
    }

    private static HashedChannelCredentials channelCredsFromProto(Any cred)
        throws GrpcServiceParseException {
      String typeUrl = cred.getTypeUrl();
      try {
        switch (typeUrl) {
          case GOOGLE_DEFAULT_CREDENTIALS_TYPE_URL:
            return HashedChannelCredentials.of(GoogleDefaultChannelCredentials.create(),
                cred.hashCode());
          case INSECURE_CREDENTIALS_TYPE_URL:
            return HashedChannelCredentials.of(InsecureChannelCredentials.create(),
                cred.hashCode());
          case XDS_CREDENTIALS_TYPE_URL:
            XdsCredentials xdsConfig = cred.unpack(XdsCredentials.class);
            HashedChannelCredentials fallbackCreds =
                channelCredsFromProto(xdsConfig.getFallbackCredentials());
            return HashedChannelCredentials.of(
                XdsChannelCredentials.create(fallbackCreds.channelCredentials()), cred.hashCode());
          case LOCAL_CREDENTIALS_TYPE_URL:
            // TODO(sauravzg) : What's the java alternative to LocalCredentials.
            throw new GrpcServiceParseException("LocalCredentials are not yet supported.");
          case TLS_CREDENTIALS_TYPE_URL:
            // TODO(sauravzg) : How to instantiate a TlsChannelCredentials from TlsCredentials
            // proto?
            throw new GrpcServiceParseException("TlsCredentials are not yet supported.");
          default:
            throw new GrpcServiceParseException("Unsupported channel credentials type: " + typeUrl);
        }
      } catch (InvalidProtocolBufferException e) {
        // TODO(sauravzg): Add unit tests when we have a solution for TLS creds.
        // This code is as of writing unreachable because all channel credential message
        // types except TLS are empty messages.
        throw new GrpcServiceParseException(
            "Failed to parse channel credentials: " + e.getMessage());
      }
    }

    private static CallCredentials callCredsFromProto(Any cred) throws GrpcServiceParseException {
      try {
        AccessTokenCredentials accessToken = cred.unpack(AccessTokenCredentials.class);
        // TODO(sauravzg): Verify if the current behavior is per spec.The `AccessTokenCredentials`
        // config doesn't have any timeout/refresh, so set the token to never expire.
        return MoreCallCredentials.from(OAuth2Credentials
            .create(new AccessToken(accessToken.getToken(), new Date(Long.MAX_VALUE))));
      } catch (InvalidProtocolBufferException e) {
        throw new GrpcServiceParseException(
            "Unsupported call credentials type: " + cred.getTypeUrl());
      }
    }

    private static HashedChannelCredentials extractChannelCredentials(
        List<Any> channelCredentialPlugins) throws GrpcServiceParseException {
      return getFirstSupported(channelCredentialPlugins, GoogleGrpcConfig::channelCredsFromProto,
          "channel_credentials");
    }

    private static CallCredentials extractCallCredentials(List<Any> callCredentialPlugins)
        throws GrpcServiceParseException {
      return getFirstSupported(callCredentialPlugins, GoogleGrpcConfig::callCredsFromProto,
          "call_credentials");
    }
  }

  /**
   * A container for {@link ChannelCredentials} and a hash for the purpose of caching.
   */
  @AutoValue
  public abstract static class HashedChannelCredentials {
    /**
     * Creates a new {@link HashedChannelCredentials} instance.
     *
     * @param creds The channel credentials.
     * @param hash The hash of the credentials.
     * @return A new {@link HashedChannelCredentials} instance.
     */
    public static HashedChannelCredentials of(ChannelCredentials creds, int hash) {
      return new AutoValue_GrpcServiceConfig_HashedChannelCredentials(creds, hash);
    }

    /**
     * Returns the channel credentials.
     */
    public abstract ChannelCredentials channelCredentials();

    /**
     * Returns the hash of the credentials.
     */
    public abstract int hash();
  }

  /**
   * Defines a generic interface for parsing a configuration of type {@code U} into a result of type
   * {@code T}. This functional interface is used to abstract the parsing logic for different parts
   * of the GrpcService configuration.
   *
   * @param <T> The type of the object that will be returned after parsing.
   * @param <U> The type of the configuration object that will be parsed.
   */
  private interface Parser<T, U> {

    /**
     * Parses the given configuration.
     *
     * @param config The configuration object to parse.
     * @return The parsed object of type {@code T}.
     * @throws GrpcServiceParseException if an error occurs during parsing.
     */
    T parse(U config) throws GrpcServiceParseException;
  }
}
