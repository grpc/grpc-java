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

import com.google.common.io.BaseEncoding;
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
import io.grpc.Metadata;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GrpcServiceConfigTest {

  @Test
  public void fromProto_success() throws GrpcServiceParseException {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).addCallCredentialsPlugin(accessTokenCreds)
        .build();
    HeaderValue asciiHeader =
        HeaderValue.newBuilder().setKey("test_key").setValue("test_value").build();
    HeaderValue binaryHeader = HeaderValue.newBuilder().setKey("test_key-bin")
        .setValue(
            BaseEncoding.base64().encode("test_value_binary".getBytes(StandardCharsets.UTF_8)))
        .build();
    Duration timeout = Duration.newBuilder().setSeconds(10).build();
    GrpcService grpcService =
        GrpcService.newBuilder().setGoogleGrpc(googleGrpc).addInitialMetadata(asciiHeader)
            .addInitialMetadata(binaryHeader).setTimeout(timeout).build();

    GrpcServiceConfig config = GrpcServiceConfig.fromProto(grpcService);

    // Assert target URI
    assertThat(config.googleGrpc().target()).isEqualTo("test_uri");

    // Assert channel credentials
    assertThat(config.googleGrpc().hashedChannelCredentials().channelCredentials())
        .isInstanceOf(InsecureChannelCredentials.class);
    assertThat(config.googleGrpc().hashedChannelCredentials().hash())
        .isEqualTo(insecureCreds.hashCode());

    // Assert call credentials
    assertThat(config.googleGrpc().callCredentials().getClass().getName())
        .isEqualTo("io.grpc.auth.GoogleAuthLibraryCallCredentials");

    // Assert initial metadata
    assertThat(config.initialMetadata().isPresent()).isTrue();
    assertThat(config.initialMetadata().get()
        .get(Metadata.Key.of("test_key", Metadata.ASCII_STRING_MARSHALLER)))
            .isEqualTo("test_value");
    assertThat(config.initialMetadata().get()
        .get(Metadata.Key.of("test_key-bin", Metadata.BINARY_BYTE_MARSHALLER)))
            .isEqualTo("test_value_binary".getBytes(StandardCharsets.UTF_8));

    // Assert timeout
    assertThat(config.timeout().isPresent()).isTrue();
    assertThat(config.timeout().get()).isEqualTo(java.time.Duration.ofSeconds(10));
  }

  @Test
  public void fromProto_minimalSuccess_defaults() throws GrpcServiceParseException {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).addCallCredentialsPlugin(accessTokenCreds)
        .build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceConfig config = GrpcServiceConfig.fromProto(grpcService);

    assertThat(config.googleGrpc().target()).isEqualTo("test_uri");
    assertThat(config.initialMetadata().isPresent()).isFalse();
    assertThat(config.timeout().isPresent()).isFalse();
  }

  @Test
  public void fromProto_missingGoogleGrpc() {
    GrpcService grpcService = GrpcService.newBuilder().build();
    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> GrpcServiceConfig.fromProto(grpcService));
    assertThat(exception).hasMessageThat()
        .startsWith("Unsupported: GrpcService must have GoogleGrpc, got: ");
  }

  @Test
  public void fromProto_emptyCallCredentials() {
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();
    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> GrpcServiceConfig.fromProto(grpcService));
    assertThat(exception).hasMessageThat()
        .isEqualTo("No valid supported call_credentials found. Errors: []");
  }

  @Test
  public void fromProto_emptyChannelCredentials() {
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addCallCredentialsPlugin(accessTokenCreds).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();
    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> GrpcServiceConfig.fromProto(grpcService));
    assertThat(exception).hasMessageThat()
        .isEqualTo("No valid supported channel_credentials found. Errors: []");
  }

  @Test
  public void fromProto_googleDefaultCredentials() throws GrpcServiceParseException {
    Any googleDefaultCreds = Any.pack(GoogleDefaultCredentials.getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(googleDefaultCreds).addCallCredentialsPlugin(accessTokenCreds)
        .build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceConfig config = GrpcServiceConfig.fromProto(grpcService);

    assertThat(config.googleGrpc().hashedChannelCredentials().channelCredentials())
        .isInstanceOf(io.grpc.CompositeChannelCredentials.class);
    assertThat(config.googleGrpc().hashedChannelCredentials().hash())
        .isEqualTo(googleDefaultCreds.hashCode());
  }

  @Test
  public void fromProto_localCredentials() throws GrpcServiceParseException {
    Any localCreds = Any.pack(LocalCredentials.getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(localCreds).addCallCredentialsPlugin(accessTokenCreds).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> GrpcServiceConfig.fromProto(grpcService));
    assertThat(exception).hasMessageThat().contains("LocalCredentials are not yet supported.");
  }

  @Test
  public void fromProto_xdsCredentials_withInsecureFallback() throws GrpcServiceParseException {
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

    GrpcServiceConfig config = GrpcServiceConfig.fromProto(grpcService);

    assertThat(config.googleGrpc().hashedChannelCredentials().channelCredentials())
        .isInstanceOf(io.grpc.ChannelCredentials.class);
    assertThat(config.googleGrpc().hashedChannelCredentials().hash())
        .isEqualTo(xdsCredsAny.hashCode());
  }

  @Test
  public void fromProto_tlsCredentials_notSupported() {
    Any tlsCreds = Any
        .pack(io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.tls.v3.TlsCredentials
            .getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(tlsCreds).addCallCredentialsPlugin(accessTokenCreds).build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> GrpcServiceConfig.fromProto(grpcService));
    assertThat(exception).hasMessageThat().contains("TlsCredentials are not yet supported.");
  }

  @Test
  public void fromProto_invalidChannelCredentialsProto() {
    // Pack a Duration proto, but try to unpack it as GoogleDefaultCredentials
    Any invalidCreds = Any.pack(com.google.protobuf.Duration.getDefaultInstance());
    Any accessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("test_token").build());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(invalidCreds).addCallCredentialsPlugin(accessTokenCreds)
        .build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> GrpcServiceConfig.fromProto(grpcService));
    assertThat(exception).hasMessageThat()
        .contains("No valid supported channel_credentials found. Errors: [Unsupported channel "
            + "credentials type: type.googleapis.com/google.protobuf.Duration");
  }

  @Test
  public void fromProto_invalidCallCredentialsProto() {
    // Pack a Duration proto, but try to unpack it as AccessTokenCredentials
    Any insecureCreds = Any.pack(InsecureCredentials.getDefaultInstance());
    Any invalidCallCredentials = Any.pack(Duration.getDefaultInstance());
    GrpcService.GoogleGrpc googleGrpc = GrpcService.GoogleGrpc.newBuilder().setTargetUri("test_uri")
        .addChannelCredentialsPlugin(insecureCreds).addCallCredentialsPlugin(invalidCallCredentials)
        .build();
    GrpcService grpcService = GrpcService.newBuilder().setGoogleGrpc(googleGrpc).build();

    GrpcServiceParseException exception = assertThrows(GrpcServiceParseException.class,
        () -> GrpcServiceConfig.fromProto(grpcService));
    assertThat(exception).hasMessageThat().contains("Unsupported call credentials type:");
  }
}

