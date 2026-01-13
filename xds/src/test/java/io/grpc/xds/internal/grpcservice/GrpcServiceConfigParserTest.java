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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.extensions.grpc_service.call_credentials.access_token.v3.AccessTokenCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.google_default.v3.GoogleDefaultCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.local.v3.LocalCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.xds.v3.XdsCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.xds.internal.grpcservice.GrpcServiceXdsContext.AllowedGrpcService;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GrpcServiceConfigParserTest {

  private static final String CALL_CREDENTIALS_CLASS_NAME =
      "io.grpc.xds.internal.grpcservice.GrpcServiceConfigParser"
          + "$SecurityAwareAccessTokenCredentials";

  @Test
  public void parse_success() throws GrpcServiceParseException {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).addCallCredentialsPlugin(accessTokenCreds)
        .build();
    HeaderValue asciiHeader =
        HeaderValue.newBuilder().setKey("test_key").setValue("test_value").build();
    HeaderValue binaryHeader =
        HeaderValue.newBuilder().setKey("test_key-bin").setRawValue(com.google.protobuf.ByteString
            .copyFrom("test_value_binary".getBytes(StandardCharsets.UTF_8))).build();
    Duration timeout = Duration.newBuilder().setSeconds(10).build();
    GrpcService grpcService =
        GrpcService.newBuilder().setGoogleGrpc(googleGrpc).addInitialMetadata(asciiHeader)
            .addInitialMetadata(binaryHeader).setTimeout(timeout).build();

    GrpcServiceConfig config = GrpcServiceConfigParser.parse(grpcService,
            io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextTestUtil.dummyProvider());

    // Assert target URI
    assertThat(config.googleGrpc().target()).isEqualTo("test_uri");

    // Assert channel credentials
    assertThat(config.googleGrpc().configuredChannelCredentials().channelCredentials())
        .isInstanceOf(InsecureChannelCredentials.class);
    GrpcServiceConfigParser.ProtoChannelCredsConfig credsConfig =
        (GrpcServiceConfigParser.ProtoChannelCredsConfig)
            config.googleGrpc().configuredChannelCredentials().channelCredsConfig();
    assertThat(credsConfig.configProto()).isEqualTo(insecureCreds);

    // Assert call credentials
    assertThat(config.googleGrpc().callCredentials().isPresent()).isTrue();
    assertThat(config.googleGrpc().callCredentials().get().getClass().getName())
        .isEqualTo(CALL_CREDENTIALS_CLASS_NAME);

    // Assert initial metadata
    assertThat(config.initialMetadata()).isNotEmpty();
    assertThat(config.initialMetadata().get(0).key()).isEqualTo("test_key");
    assertThat(config.initialMetadata().get(0).value().get()).isEqualTo("test_value");
    assertThat(config.initialMetadata().get(1).key()).isEqualTo("test_key-bin");
    assertThat(config.initialMetadata().get(1).rawValue().get().toByteArray())
        .isEqualTo("test_value_binary".getBytes(StandardCharsets.UTF_8));

    // Assert timeout
    assertThat(config.timeout().isPresent()).isTrue();
    assertThat(config.timeout().get()).isEqualTo(java.time.Duration.ofSeconds(10));
  }

  @Test
  public void parse_minimalSuccess_defaults() throws GrpcServiceParseException {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).addCallCredentialsPlugin(accessTokenCreds)
        .build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceConfig config = GrpcServiceConfigParser.parse(grpcService,
            io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextTestUtil.dummyProvider());

    assertThat(config.googleGrpc().target()).isEqualTo("test_uri");
    assertThat(config.initialMetadata()).isEmpty();
    assertThat(config.timeout().isPresent()).isFalse();
  }

  @Test
  public void parse_missingGoogleGrpc() {
    GrpcService grpcService = GrpcService.newBuilder().build();
    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> GrpcServiceConfigParser.parse(grpcService,
                    io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextTestUtil
                            .dummyProvider()));
    assertThat(exception).hasMessageThat()
        .startsWith("Unsupported: GrpcService must have GoogleGrpc, got: ");
  }

  @Test
  public void parse_emptyCallCredentials() throws GrpcServiceParseException {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceConfig config = GrpcServiceConfigParser.parse(grpcService,
            io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextTestUtil.dummyProvider());

    assertThat(config.googleGrpc().callCredentials().isPresent()).isFalse();
  }

  @Test
  public void parse_emptyChannelCredentials() {
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addCallCredentialsPlugin(accessTokenCreds).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();
    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> GrpcServiceConfigParser.parse(grpcService,
                    io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextTestUtil
                            .dummyProvider()));
    assertThat(exception).hasMessageThat()
        .isEqualTo("No valid supported channel_credentials found");
  }

  @Test
  public void parse_googleDefaultCredentials() throws GrpcServiceParseException {
    Any googleDefaultCreds = Any.pack(GoogleDefaultCredentials.getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(googleDefaultCreds).addCallCredentialsPlugin(accessTokenCreds)
        .build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceConfig config = GrpcServiceConfigParser.parse(grpcService,
            io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextTestUtil.dummyProvider());

    assertThat(config.googleGrpc().configuredChannelCredentials().channelCredentials())
        .isInstanceOf(io.grpc.CompositeChannelCredentials.class);
    GrpcServiceConfigParser.ProtoChannelCredsConfig credsConfig =
        (GrpcServiceConfigParser.ProtoChannelCredsConfig)
            config.googleGrpc().configuredChannelCredentials().channelCredsConfig();
    assertThat(credsConfig.configProto()).isEqualTo(googleDefaultCreds);
  }

  @Test
  public void parse_localCredentials() throws GrpcServiceParseException {
    Any localCreds = Any.pack(LocalCredentials.getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(localCreds).addCallCredentialsPlugin(accessTokenCreds).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class,
            () -> GrpcServiceConfigParser.parse(grpcService,
                    io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextTestUtil
                            .dummyProvider()));
    assertThat(exception).hasMessageThat()
        .contains("LocalCredentials are not supported in grpc-java");
  }

  @Test
  public void parse_xdsCredentials_withInsecureFallback() throws GrpcServiceParseException {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    XdsCredentials xdsCreds =
        XdsCredentials.newBuilder().setFallbackCredentials(insecureCreds).build();
    Any xdsCredsAny = Any.pack(xdsCreds);
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(xdsCredsAny).addCallCredentialsPlugin(accessTokenCreds)
        .build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceConfig config = GrpcServiceConfigParser.parse(grpcService,
            io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextTestUtil.dummyProvider());

    assertThat(config.googleGrpc().configuredChannelCredentials().channelCredentials())
        .isInstanceOf(io.grpc.ChannelCredentials.class);
    GrpcServiceConfigParser.ProtoChannelCredsConfig credsConfig =
        (GrpcServiceConfigParser.ProtoChannelCredsConfig)
            config.googleGrpc().configuredChannelCredentials().channelCredsConfig();
    assertThat(credsConfig.configProto()).isEqualTo(xdsCredsAny);
  }

  @Test
  public void parse_tlsCredentials_notSupported() {
    Any tlsCreds = Any
        .pack(io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.tls.v3.TlsCredentials
            .getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(tlsCreds).addCallCredentialsPlugin(accessTokenCreds).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class,
            () -> GrpcServiceConfigParser.parse(grpcService,
                    io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextTestUtil
                            .dummyProvider()));
    assertThat(exception).hasMessageThat()
        .contains("TlsCredentials input stream construction pending");
  }

  @Test
  public void parse_invalidChannelCredentialsProto() {
    // Pack a Duration proto, but try to unpack it as GoogleDefaultCredentials
    Any invalidCreds = Any.pack(com.google.protobuf.Duration.getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(invalidCreds).addCallCredentialsPlugin(accessTokenCreds)
        .build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> GrpcServiceConfigParser.parse(grpcService,
                    io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextTestUtil
                            .dummyProvider()));
    assertThat(exception).hasMessageThat().contains("No valid supported channel_credentials found");
  }

  @Test
  public void parse_ignoredUnsupportedCallCredentialsProto() throws GrpcServiceParseException {
    // Pack a Duration proto, but try to unpack it as AccessTokenCredentials
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    Any invalidCallCredentials = Any.pack(Duration.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).addCallCredentialsPlugin(invalidCallCredentials)
        .build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceConfig config = GrpcServiceConfigParser.parse(grpcService,
            io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextTestUtil.dummyProvider());
    assertThat(config.googleGrpc().callCredentials().isPresent()).isFalse();
  }

  @Test
  public void parse_invalidAccessTokenCallCredentialsProto() {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    Any invalidCallCredentials = Any.pack(AccessTokenCredentials.newBuilder().setToken("").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).addCallCredentialsPlugin(invalidCallCredentials)
        .build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> GrpcServiceConfigParser.parse(grpcService,
                    io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextTestUtil
                            .dummyProvider()));
    assertThat(exception).hasMessageThat()
        .contains("Missing or empty access token in call credentials");
  }

  @Test
  public void parse_multipleCallCredentials() throws GrpcServiceParseException {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    Any accessTokenCreds1 =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("token1").build());
    Any accessTokenCreds2 =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("token2").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).addCallCredentialsPlugin(accessTokenCreds1)
        .addCallCredentialsPlugin(accessTokenCreds2).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceConfig config = GrpcServiceConfigParser.parse(grpcService,
            io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextTestUtil.dummyProvider());

    assertThat(config.googleGrpc().callCredentials().isPresent()).isTrue();
    assertThat(config.googleGrpc().callCredentials().get())
        .isInstanceOf(io.grpc.CompositeCallCredentials.class);
  }

  @Test
  public void parse_untrustedControlPlane_withoutOverride() {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceXdsContext untrustedContext =
            GrpcServiceXdsContext.create(false, java.util.Optional.empty(), true);

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
            () -> GrpcServiceConfigParser.parse(grpcService, targetUri -> untrustedContext));
    assertThat(exception).hasMessageThat()
        .contains("Untrusted xDS server & URI not found in allowed_grpc_services");
  }

  @Test
  public void parse_untrustedControlPlane_withOverride() throws GrpcServiceParseException {
    // The proto credentials (insecure) should be ignored in favor of the override (google default)
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    ConfiguredChannelCredentials overrideChannelCreds = ConfiguredChannelCredentials.create(
            io.grpc.alts.GoogleDefaultChannelCredentials.create(),
            new GrpcServiceConfigParser.ProtoChannelCredsConfig(
                GrpcServiceConfigParser.GOOGLE_DEFAULT_CREDENTIALS_TYPE_URL,
                Any.pack(GoogleDefaultCredentials.getDefaultInstance())));
    AllowedGrpcService override = AllowedGrpcService.builder()
            .configuredChannelCredentials(overrideChannelCreds).build();

    GrpcServiceXdsContext untrustedContext =
            GrpcServiceXdsContext.create(false, java.util.Optional.of(override), true);

    GrpcServiceConfig config =
            GrpcServiceConfigParser.parse(grpcService, targetUri -> untrustedContext);

    // Assert channel credentials are the override, not the proto's insecure creds
    assertThat(config.googleGrpc().configuredChannelCredentials().channelCredentials())
            .isInstanceOf(io.grpc.CompositeChannelCredentials.class);
  }

  @Test
  public void parse_invalidTimeout() {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).build();

    // Negative timeout
    Duration timeout = Duration.newBuilder().setSeconds(-10).build();
    GrpcService grpcService = GrpcService.newBuilder()
        .setGoogleGrpc(googleGrpc).setTimeout(timeout).build();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
            () -> GrpcServiceConfigParser.parse(grpcService,
                    GrpcServiceXdsContextTestUtil.dummyProvider()));
    assertThat(exception).hasMessageThat()
        .contains("Timeout must be strictly positive");

    // Zero timeout
    timeout = Duration.newBuilder().setSeconds(0).setNanos(0).build();
    GrpcService grpcServiceZero = GrpcService.newBuilder()
        .setGoogleGrpc(googleGrpc).setTimeout(timeout).build();

    exception = assertThrows(GrpcServiceParseException.class,
            () -> GrpcServiceConfigParser.parse(grpcServiceZero,
                    GrpcServiceXdsContextTestUtil.dummyProvider()));
    assertThat(exception).hasMessageThat()
        .contains("Timeout must be strictly positive");
  }

  @Test
  public void parseGoogleGrpcConfig_unsupportedScheme() {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder()
        .setTargetUri("unknown://test")
        .addChannelCredentialsPlugin(insecureCreds).build();

    GrpcServiceXdsContext context =
            GrpcServiceXdsContext.create(true, java.util.Optional.empty(), false);

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
            () -> GrpcServiceConfigParser.parseGoogleGrpcConfig(googleGrpc, targetUri -> context));
    assertThat(exception).hasMessageThat()
        .contains("Target URI scheme is not resolvable");
  }
}
