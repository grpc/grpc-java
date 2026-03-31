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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.extensions.grpc_service.call_credentials.access_token.v3.AccessTokenCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.google_default.v3.GoogleDefaultCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.local.v3.LocalCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.xds.v3.XdsCredentials;
import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.CompositeCallCredentials;
import io.grpc.CompositeChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import io.grpc.alts.GoogleDefaultChannelCredentials;
import io.grpc.xds.client.AllowedGrpcServices;
import io.grpc.xds.client.AllowedGrpcServices.AllowedGrpcService;
import io.grpc.xds.client.Bootstrapper.BootstrapInfo;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.ConfiguredChannelCredentials;
import io.grpc.xds.client.EnvoyProtoData.Node;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig;
import io.grpc.xds.internal.grpcservice.GrpcServiceParseException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class GrpcServiceConfigParserTest {

  private static final String CALL_CREDENTIALS_CLASS_NAME =
      "io.grpc.xds.GrpcServiceConfigParser$SecurityAwareAccessTokenCredentials";

  private static BootstrapInfo dummyBootstrapInfo() {
    return dummyBootstrapInfo(Optional.empty());
  }

  private static BootstrapInfo dummyBootstrapInfo(Optional<Object> implSpecificObject) {
    return BootstrapInfo.builder()
        .servers(Collections
            .singletonList(ServerInfo.create("test_target", Collections.emptyMap())))
        .node(Node.newBuilder().build()).implSpecificObject(implSpecificObject).build();
  }

  private static ServerInfo dummyServerInfo() {
    return dummyServerInfo(true);
  }

  private static ServerInfo dummyServerInfo(boolean isTrusted) {
    return ServerInfo.create("test_target", Collections.emptyMap(), false, isTrusted, false,
        false);
  }

  private static GrpcServiceConfig parse(
      GrpcService grpcServiceProto, BootstrapInfo bootstrapInfo,
      ServerInfo serverInfo)
      throws GrpcServiceParseException {
    return GrpcServiceConfigParser.parse(grpcServiceProto, bootstrapInfo, serverInfo);
  }

  private static GrpcServiceConfig.GoogleGrpcConfig parseGoogleGrpcConfig(
      GrpcService.GoogleGrpc googleGrpcProto, BootstrapInfo bootstrapInfo,
      ServerInfo serverInfo)
      throws GrpcServiceParseException {
    return GrpcServiceConfigParser.parseGoogleGrpcConfig(
        googleGrpcProto, bootstrapInfo, serverInfo);
  }

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
        HeaderValue.newBuilder().setKey("test_key-bin").setRawValue(ByteString
            .copyFrom("test_value_binary".getBytes(StandardCharsets.UTF_8))).build();
    Duration timeout = Duration.newBuilder().setSeconds(10).build();
    GrpcService grpcService =
        GrpcService.newBuilder().setGoogleGrpc(googleGrpc).addInitialMetadata(asciiHeader)
            .addInitialMetadata(binaryHeader).setTimeout(timeout).build();

    GrpcServiceConfig config = parse(grpcService,
            dummyBootstrapInfo(),
            dummyServerInfo());

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

    GrpcServiceConfig config = parse(grpcService,
            dummyBootstrapInfo(),
            dummyServerInfo());

    assertThat(config.googleGrpc().target()).isEqualTo("test_uri");
    assertThat(config.initialMetadata()).isEmpty();
    assertThat(config.timeout().isPresent()).isFalse();
  }

  @Test
  public void parse_missingGoogleGrpc() {
    GrpcService grpcService = GrpcService.newBuilder().build();
    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> parse(grpcService,
                    dummyBootstrapInfo(),
            dummyServerInfo()));
    assertThat(exception).hasMessageThat()
        .startsWith("Unsupported: GrpcService must have GoogleGrpc, got: ");
  }

  @Test
  public void parse_emptyCallCredentials() throws GrpcServiceParseException {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceConfig config = parse(grpcService,
            dummyBootstrapInfo(),
            dummyServerInfo());

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
        () -> parse(grpcService,
                    dummyBootstrapInfo(),
            dummyServerInfo()));
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

    GrpcServiceConfig config = parse(grpcService,
            dummyBootstrapInfo(),
            dummyServerInfo());

    assertThat(config.googleGrpc().configuredChannelCredentials().channelCredentials())
        .isInstanceOf(CompositeChannelCredentials.class);
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
            () -> parse(grpcService,
                    dummyBootstrapInfo(),
            dummyServerInfo()));
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
            dummyBootstrapInfo(),
            dummyServerInfo());

    assertThat(config.googleGrpc().configuredChannelCredentials().channelCredentials())
        .isNotNull();
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
            () -> parse(grpcService,
                    dummyBootstrapInfo(),
            dummyServerInfo()));
    assertThat(exception).hasMessageThat()
        .contains("TlsCredentials input stream construction pending");
  }

  @Test
  public void parse_invalidChannelCredentialsProto() {
    // Pack a Duration proto, but try to unpack it as GoogleDefaultCredentials
    Any invalidCreds = Any.pack(Duration.getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(invalidCreds).addCallCredentialsPlugin(accessTokenCreds)
        .build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> parse(grpcService,
                    dummyBootstrapInfo(),
            dummyServerInfo()));
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

    GrpcServiceConfig config = parse(grpcService,
            dummyBootstrapInfo(),
            dummyServerInfo());
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
        () -> parse(grpcService,
                    dummyBootstrapInfo(),
            dummyServerInfo()));
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

    GrpcServiceConfig config = parse(grpcService,
            dummyBootstrapInfo(),
            dummyServerInfo());

    assertThat(config.googleGrpc().callCredentials().isPresent()).isTrue();
    assertThat(config.googleGrpc().callCredentials().get())
        .isInstanceOf(CompositeCallCredentials.class);
  }

  @Test
  public void parse_untrustedControlPlane_withoutOverride() {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    BootstrapInfo untrustedBootstrapInfo = dummyBootstrapInfo(Optional.empty());
    ServerInfo untrustedServerInfo =
        dummyServerInfo(false);

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
            () -> parse(
            grpcService, untrustedBootstrapInfo, untrustedServerInfo));
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
            GoogleDefaultChannelCredentials.create(),
            new GrpcServiceConfigParser.ProtoChannelCredsConfig(
                GrpcServiceConfigParser.GOOGLE_DEFAULT_CREDENTIALS_TYPE_URL,
                Any.pack(GoogleDefaultCredentials.getDefaultInstance())));
    AllowedGrpcService override = AllowedGrpcService.builder()
            .configuredChannelCredentials(overrideChannelCreds).build();
    AllowedGrpcServices servicesMap =
            AllowedGrpcServices.create(
                ImmutableMap.of("test_uri", override));

    BootstrapInfo untrustedBootstrapInfo =
        dummyBootstrapInfo(Optional.of(GrpcBootstrapImplConfig.create(servicesMap)));
    ServerInfo untrustedServerInfo =
        dummyServerInfo(false);

    GrpcServiceConfig config =
            parse(grpcService, untrustedBootstrapInfo, untrustedServerInfo);

    // Assert channel credentials are the override, not the proto's insecure creds
    assertThat(config.googleGrpc().configuredChannelCredentials().channelCredentials())
            .isInstanceOf(CompositeChannelCredentials.class);
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
            () -> parse(grpcService,
                    dummyBootstrapInfo(),
            dummyServerInfo()));
    assertThat(exception).hasMessageThat()
        .contains("Timeout must be strictly positive");

    // Zero timeout
    timeout = Duration.newBuilder().setSeconds(0).setNanos(0).build();
    GrpcService grpcServiceZero = GrpcService.newBuilder()
        .setGoogleGrpc(googleGrpc).setTimeout(timeout).build();

    exception = assertThrows(GrpcServiceParseException.class,
            () -> parse(grpcServiceZero,
                    dummyBootstrapInfo(),
            dummyServerInfo()));
    assertThat(exception).hasMessageThat()
        .contains("Timeout must be strictly positive");
  }

  @Test
  public void parseGoogleGrpcConfig_unsupportedScheme() {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder()
        .setTargetUri("unknown://test")
        .addChannelCredentialsPlugin(insecureCreds).build();

    BootstrapInfo bootstrapInfo = dummyBootstrapInfo();
    ServerInfo serverInfo = dummyServerInfo();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> parseGoogleGrpcConfig(
            googleGrpc, bootstrapInfo, serverInfo));
    assertThat(exception).hasMessageThat()
        .contains("Target URI scheme is not resolvable");
  }

  @Test
  public void parse_disallowedInitialMetadata() {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).build();
    HeaderValue disallowedHeader =
        HeaderValue.newBuilder().setKey("host").setValue("test_value").build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc)
        .addInitialMetadata(disallowedHeader).build();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> parse(grpcService, dummyBootstrapInfo(), dummyServerInfo()));
    assertThat(exception).hasMessageThat().contains("Invalid initial metadata header: host");
  }

  @Test
  public void parse_invalidDuration() {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).build();

    Duration timeout = Duration.newBuilder().setSeconds(10).setNanos(1_000_000_000).build();
    GrpcService grpcService = GrpcService.newBuilder()
        .setGoogleGrpc(googleGrpc).setTimeout(timeout).build();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> parse(grpcService, dummyBootstrapInfo(), dummyServerInfo()));
    assertThat(exception).hasMessageThat()
        .contains("Timeout must be strictly positive and valid");
  }

  @Test
  public void parse_invalidChannelCredsProto() {
    Any invalidCreds = Any.newBuilder()
        .setTypeUrl(GrpcServiceConfigParser.XDS_CREDENTIALS_TYPE_URL)
        .setValue(ByteString.copyFrom(new byte[]{1, 2, 3})).build();
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(invalidCreds).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> parse(grpcService, dummyBootstrapInfo(), dummyServerInfo()));
    assertThat(exception).hasMessageThat().contains("Failed to parse channel credentials");
  }

  @Test
  public void parse_unsupportedXdsFallbackCreds() {
    Any unsupportedFallback = Any.pack(Duration.getDefaultInstance());
    XdsCredentials xds =
        XdsCredentials.newBuilder().setFallbackCredentials(unsupportedFallback).build();
    Any xdsCredsAny = Any.newBuilder()
        .setTypeUrl(GrpcServiceConfigParser.XDS_CREDENTIALS_TYPE_URL)
        .setValue(xds.toByteString()).build();
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(xdsCredsAny).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> parse(grpcService, dummyBootstrapInfo(), dummyServerInfo()));
    assertThat(exception).hasMessageThat()
        .contains("Unsupported fallback credentials type for XdsCredentials");
  }

  @Test
  public void parse_invalidCallCredsProto() {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    // We just create an Any representing AccessTokenCredentials but with invalid bytes
    Any invalidCallCreds = Any.newBuilder()
        .setTypeUrl(Any.pack(AccessTokenCredentials.getDefaultInstance()).getTypeUrl())
        .setValue(ByteString.copyFrom(new byte[]{1, 2, 3})).build();

    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).addCallCredentialsPlugin(invalidCallCreds)
        .build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> parse(grpcService, dummyBootstrapInfo(), dummyServerInfo()));
    assertThat(exception).hasMessageThat().contains("Failed to parse access token credentials");
  }

  @Test
  public void parseGoogleGrpcConfig_malformedUriThrows() {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri(":::::")
        .addChannelCredentialsPlugin(insecureCreds).build();

    BootstrapInfo bootstrapInfo = dummyBootstrapInfo();
    ServerInfo serverInfo = dummyServerInfo();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> parseGoogleGrpcConfig(googleGrpc, bootstrapInfo, serverInfo));
    assertThat(exception).hasMessageThat().contains("Target URI scheme is not resolvable");
  }

  @Test
  public void parseGoogleGrpcConfig_untrustedWithCallCredentialsOverride() throws Exception {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).build();

    ConfiguredChannelCredentials overrideChannelCreds =
        ConfiguredChannelCredentials.create(GoogleDefaultChannelCredentials.create(),
            new GrpcServiceConfigParser.ProtoChannelCredsConfig(
                GrpcServiceConfigParser.GOOGLE_DEFAULT_CREDENTIALS_TYPE_URL,
                Any.pack(GoogleDefaultCredentials.getDefaultInstance())));

    CallCredentials fakeCallCreds = Mockito.mock(CallCredentials.class);
    AllowedGrpcService override = AllowedGrpcService.builder()
        .configuredChannelCredentials(overrideChannelCreds).callCredentials(fakeCallCreds).build();

    AllowedGrpcServices servicesMap =
        AllowedGrpcServices
            .create(ImmutableMap.of("test_uri", override));

    BootstrapInfo untrustedBootstrapInfo =
        dummyBootstrapInfo(Optional.of(GrpcBootstrapImplConfig.create(servicesMap)));
    ServerInfo untrustedServerInfo = dummyServerInfo(false);

    GrpcServiceConfig.GoogleGrpcConfig config =
        parseGoogleGrpcConfig(googleGrpc, untrustedBootstrapInfo, untrustedServerInfo);

    assertThat(config.callCredentials().isPresent()).isTrue();
    assertThat(config.callCredentials().get()).isSameInstanceAs(fakeCallCreds);
  }

  @Test
  public void protoChannelCredsConfig_equalsAndHashCode() {
    Any insecureCreds1 = Any.pack(InsecureCredentials.getDefaultInstance());
    Any insecureCreds2 = Any.pack(InsecureCredentials.getDefaultInstance());
    Any localCreds = Any.pack(LocalCredentials.getDefaultInstance());

    GrpcServiceConfigParser.ProtoChannelCredsConfig config1 =
        new GrpcServiceConfigParser.ProtoChannelCredsConfig("type1", insecureCreds1);
    GrpcServiceConfigParser.ProtoChannelCredsConfig config1Equivalent =
        new GrpcServiceConfigParser.ProtoChannelCredsConfig("type1", insecureCreds2);
    GrpcServiceConfigParser.ProtoChannelCredsConfig configDifferentType =
        new GrpcServiceConfigParser.ProtoChannelCredsConfig("type2", insecureCreds1);
    GrpcServiceConfigParser.ProtoChannelCredsConfig configDifferentProto =
        new GrpcServiceConfigParser.ProtoChannelCredsConfig("type1", localCreds);

    assertThat(config1.type()).isEqualTo("type1");
    assertThat(config1.equals(config1)).isTrue();
    assertThat(config1.equals(null)).isFalse();
    assertThat(config1.equals(new Object())).isFalse();
    assertThat(config1.equals(config1Equivalent)).isTrue();
    assertThat(config1.hashCode()).isEqualTo(config1Equivalent.hashCode());
    assertThat(config1.equals(configDifferentType)).isFalse();
    assertThat(config1.equals(configDifferentProto)).isFalse();
  }

  static class RecordingMetadataApplier extends CallCredentials.MetadataApplier {
    boolean applied = false;
    boolean failed = false;
    Metadata appliedHeaders = null;

    @Override
    public void apply(Metadata headers) {
      applied = true;
      appliedHeaders = headers;
    }

    @Override
    public void fail(Status status) {
      failed = true;
    }
  }

  static class FakeRequestInfo extends CallCredentials.RequestInfo {
    private final SecurityLevel securityLevel;
    private final MethodDescriptor<?, ?> methodDescriptor;

    FakeRequestInfo(SecurityLevel securityLevel) {
      this.securityLevel = securityLevel;
      this.methodDescriptor = MethodDescriptor.<Void, Void>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("test_service/test_method")
          .setRequestMarshaller(new NoopMarshaller<Void>())
          .setResponseMarshaller(new NoopMarshaller<Void>())
          .build();
    }

    private static class NoopMarshaller<T> implements MethodDescriptor.Marshaller<T> {
      @Override
      public InputStream stream(T value) {
        return null;
      }

      @Override
      public T parse(InputStream stream) {
        return null;
      }
    }

    @Override
    public MethodDescriptor<?, ?> getMethodDescriptor() {
      return methodDescriptor;
    }

    @Override
    public SecurityLevel getSecurityLevel() {
      return securityLevel;
    }

    @Override
    public String getAuthority() {
      return "dummy-authority";
    }

    @Override
    public Attributes getTransportAttrs() {
      return Attributes.EMPTY;
    }
  }


  @Test
  public void securityAwareCredentials_secureConnection_appliesToken() throws Exception {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder()
        .setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds)
        .addCallCredentialsPlugin(accessTokenCreds)
        .build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceConfig config = parse(grpcService,
        dummyBootstrapInfo(),
            dummyServerInfo());

    CallCredentials creds = config.googleGrpc().callCredentials().get();
    RecordingMetadataApplier applier = new RecordingMetadataApplier();
    CountDownLatch latch = new CountDownLatch(1);

    creds.applyRequestMetadata(
        new FakeRequestInfo(SecurityLevel.PRIVACY_AND_INTEGRITY),
        Runnable::run, // Use direct executor to avoid async issues in test
        new CallCredentials.MetadataApplier() {
          @Override
          public void apply(Metadata headers) {
            applier.apply(headers);
            latch.countDown();
          }

          @Override
          public void fail(Status status) {
            applier.fail(status);
            latch.countDown();
          }
        });

    latch.await(5, TimeUnit.SECONDS);
    assertThat(applier.applied).isTrue();
    assertThat(applier.appliedHeaders.get(
        Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("Bearer test_token");
  }

  @Test
  public void securityAwareCredentials_insecureConnection_appliesEmptyMetadata() throws Exception {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder()
        .setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds)
        .addCallCredentialsPlugin(accessTokenCreds)
        .build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceConfig config = parse(grpcService,
        dummyBootstrapInfo(),
            dummyServerInfo());

    CallCredentials creds = config.googleGrpc().callCredentials().get();
    RecordingMetadataApplier applier = new RecordingMetadataApplier();

    creds.applyRequestMetadata(
        new FakeRequestInfo(SecurityLevel.NONE),
        Runnable::run,
        applier);

    assertThat(applier.applied).isTrue();
    assertThat(applier.appliedHeaders.get(
        Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)))
        .isNull();
  }


}
