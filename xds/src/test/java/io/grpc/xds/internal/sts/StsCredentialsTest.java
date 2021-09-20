/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds.internal.sts;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpStatusCodes;
import com.google.api.client.http.json.JsonHttpContent;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.AccessToken;
import com.google.common.collect.Range;
import com.google.common.io.Files;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.auth.MoreCallCredentials;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/** Tests for {@link StsCredentials}. */
@RunWith(JUnit4.class)
public class StsCredentialsTest {
  public static final String AUDIENCE_VALUE =
      "identitynamespace:my-trust-domain:my-identity-provider";
  public static final String STS_URL = "https://securetoken.googleapis.com/v1/identitybindingtoken";
  private static final String TOKEN_FILE_NAME = "istio-token.txt";
  static final Metadata.Key<String> KEY_FOR_AUTHORIZATION =
          Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();
  private String currentFileContents;
  private File tempTokenFile;

  @Before
  public void setUp() throws IOException {
    tempTokenFile = tempFolder.newFile(TOKEN_FILE_NAME);
    currentFileContents = "test-token-content-time-" + System.currentTimeMillis();
    Files.write(currentFileContents.getBytes(StandardCharsets.UTF_8), tempTokenFile);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testStsRequestResponse() throws IOException {
    MockHttpTransport.Builder builder = new MockHttpTransport.Builder();
    MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
    response.setContent(MOCK_RESPONSE);
    builder.setLowLevelHttpResponse(response);
    MockHttpTransport httpTransport = builder.build();
    HttpTransportFactory httpTransportFactory = mock(HttpTransportFactory.class);
    when(httpTransportFactory.create()).thenReturn(httpTransport);
    StsCredentials stsCredentials =
        StsCredentials.Factory.create(
            STS_URL, AUDIENCE_VALUE, tempTokenFile.getAbsolutePath(), httpTransportFactory);
    AccessToken token = stsCredentials.refreshAccessToken();
    assertThat(token).isNotNull();
    assertThat(token.getTokenValue()).isEqualTo(ACCESS_TOKEN);
    long diff = token.getExpirationTime().getTime() - System.currentTimeMillis();
    assertThat(diff).isIn(Range.closed(3550000L, 3650000L));
    MockLowLevelHttpRequest request = httpTransport.getLowLevelHttpRequest();
    assertThat(request.getUrl()).isEqualTo(STS_URL);
    assertThat(request.getContentType()).isEqualTo("application/json; charset=UTF-8");
    assertThat(request.getStreamingContent()).isInstanceOf(JsonHttpContent.class);
    Map<String, Object> map =
        (Map<String, Object>) ((JsonHttpContent) request.getStreamingContent()).getData();
    assertThat(map.get("subject_token_type")).isEqualTo("urn:ietf:params:oauth:token-type:jwt");
    assertThat(map.get("grant_type")).isEqualTo("urn:ietf:params:oauth:grant-type:token-exchange");
    assertThat(map.get("subject_token")).isEqualTo(currentFileContents);
    assertThat(map.get("requested_token_type"))
        .isEqualTo("urn:ietf:params:oauth:token-type:access_token");
    assertThat(map.get("scope")).isEqualTo("https://www.googleapis.com/auth/cloud-platform");
    assertThat(map.get("audience")).isEqualTo(AUDIENCE_VALUE);
  }

  @Test
  public void stsCredentialsInCallCreds() throws IOException {
    MockHttpTransport.Builder builder = new MockHttpTransport.Builder();
    MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
    response.setContent(MOCK_RESPONSE);
    builder.setLowLevelHttpResponse(response);
    MockHttpTransport httpTransport = builder.build();
    HttpTransportFactory httpTransportFactory = mock(HttpTransportFactory.class);
    when(httpTransportFactory.create()).thenReturn(httpTransport);
    StsCredentials stsCredentials =
        StsCredentials.Factory.create(
            STS_URL, AUDIENCE_VALUE, tempTokenFile.getAbsolutePath(), httpTransportFactory);
    CallCredentials callCreds = MoreCallCredentials.from(stsCredentials);
    CallCredentials.RequestInfo requestInfo = mock(CallCredentials.RequestInfo.class);
    when(requestInfo.getSecurityLevel()).thenReturn(SecurityLevel.PRIVACY_AND_INTEGRITY);
    when(requestInfo.getAuthority()).thenReturn("auth");
    MethodDescriptor.Marshaller<?> requestMarshaller = mock(MethodDescriptor.Marshaller.class);
    MethodDescriptor.Marshaller<?> responseMarshaller = mock(MethodDescriptor.Marshaller.class);
    MethodDescriptor.Builder<?, ?> mBuilder =
        MethodDescriptor.newBuilder(requestMarshaller, responseMarshaller);
    mBuilder.setType(MethodDescriptor.MethodType.UNARY);
    mBuilder.setFullMethodName("service/method");
    MethodDescriptor<?, ?> methodDescriptor = mBuilder.build();
    doReturn(methodDescriptor).when(requestInfo).getMethodDescriptor();
    CallCredentials.MetadataApplier applier = mock(CallCredentials.MetadataApplier.class);
    callCreds.applyRequestMetadata(requestInfo, MoreExecutors.directExecutor(), applier);
    ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(null);
    verify(applier).apply(metadataCaptor.capture());
    Metadata metadata = metadataCaptor.getValue();
    assertThat(metadata).isNotNull();
    String authValue = metadata.get(KEY_FOR_AUTHORIZATION);
    assertThat(authValue).isEqualTo("Bearer " + ACCESS_TOKEN);
  }

  @Test
  public void testStsRequest_exception() throws IOException {
    MockHttpTransport.Builder builder = new MockHttpTransport.Builder();
    MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
    response.setStatusCode(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
    response.setContent(MOCK_RESPONSE);
    builder.setLowLevelHttpResponse(response);
    MockHttpTransport httpTransport = builder.build();
    HttpTransportFactory httpTransportFactory = mock(HttpTransportFactory.class);
    when(httpTransportFactory.create()).thenReturn(httpTransport);
    StsCredentials stsCredentials =
            StsCredentials.Factory.create(
                    STS_URL, AUDIENCE_VALUE, tempTokenFile.getAbsolutePath(), httpTransportFactory);
    try {
      stsCredentials.refreshAccessToken();
      fail("exception expected");
    } catch (IOException ioe) {
      assertThat(ioe.getMessage()).isEqualTo("Error requesting access token");
    }
  }

  @Test
  public void testStsRequest_nonSuccessCode() throws IOException {
    MockHttpTransport.Builder builder = new MockHttpTransport.Builder();
    MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
    response.setStatusCode(HttpStatusCodes.STATUS_CODE_NO_CONTENT);
    response.setContent(MOCK_RESPONSE);
    builder.setLowLevelHttpResponse(response);
    MockHttpTransport httpTransport = builder.build();
    HttpTransportFactory httpTransportFactory = mock(HttpTransportFactory.class);
    when(httpTransportFactory.create()).thenReturn(httpTransport);
    StsCredentials stsCredentials =
            StsCredentials.Factory.create(
                    STS_URL, AUDIENCE_VALUE, tempTokenFile.getAbsolutePath(), httpTransportFactory);
    try {
      stsCredentials.refreshAccessToken();
      fail("exception expected");
    } catch (IOException ioe) {
      assertThat(ioe.getMessage()).isEqualTo("Error getting access token: 204 : null");
    }
  }

  @Test
  public void toBuilder_unsupportedException() {
    HttpTransportFactory httpTransportFactory = mock(HttpTransportFactory.class);
    StsCredentials stsCredentials =
            StsCredentials.Factory.create(
                    STS_URL, AUDIENCE_VALUE, tempTokenFile.getAbsolutePath(), httpTransportFactory);
    try {
      stsCredentials.toBuilder();
      fail("exception expected");
    } catch (UnsupportedOperationException uoe) {
      assertThat(uoe.getMessage()).isEqualTo("toBuilder not supported");
    }
  }

  @Test
  public void defaultFactory() {
    StsCredentials stsCreds = StsCredentials.Factory.getInstance()
        .create(STS_URL, AUDIENCE_VALUE, tempTokenFile.getAbsolutePath());
    assertThat(stsCreds.transportFactory).isEqualTo(StsCredentials.defaultHttpTransportFactory);
  }

  private static final String ACCESS_TOKEN = "eyJhbGciOiJSU";
  private static final String MOCK_RESPONSE =
      "{\"access_token\": \""
          + ACCESS_TOKEN
          + "\",\n"
          + "  \"issued_token_type\": \"urn:ietf:params:oauth:token-type:access_token\",\n"
          + "  \"token_type\": \"Bearer\",\n"
          + "  \"expires_in\": 3600\n"
          + "}";
}
