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

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpStatusCodes;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.http.json.JsonHttpContent;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.GenericData;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

// TODO(sanjaypujare): replace with the official implementation from google-auth once ready
/** Implementation of OAuth2 Token Exchange as per https://tools.ietf.org/html/rfc8693. */
public class StsCredentials extends GoogleCredentials {
  private static final long serialVersionUID = 6647041424685484932L;

  private static final HttpTransportFactory defaultHttpTransportFactory =
      new DefaultHttpTransportFactory();
  private static final HttpTransport netHttpTransport = new NetHttpTransport();
  private static final String CLOUD_PLATFORM_SCOPE =
      "https://www.googleapis.com/auth/cloud-platform";
  private final String sourceCredentialsFileLocation;
  private final String identityTokenEndpoint;
  private final String audience;
  private transient HttpTransportFactory transportFactory;

  private StsCredentials(
      String identityTokenEndpoint,
      String audience,
      String sourceCredentialsFileLocation,
      HttpTransportFactory transportFactory) {
    this.identityTokenEndpoint = identityTokenEndpoint;
    this.audience = audience;
    this.sourceCredentialsFileLocation = sourceCredentialsFileLocation;
    this.transportFactory = transportFactory;
  }

  /**
   * Creates an StsCredentials.
   *
   * @param identityTokenEndpoint  URL of the token exchange service to use.
   * @param audience Audience to use in the STS request.
   * @param sourceCredentialsFileLocation file-system location that contains the
   *                                      source creds e.g. JWT contents.
   */
  public static StsCredentials create(
      String identityTokenEndpoint, String audience, String sourceCredentialsFileLocation) {
    return create(
        identityTokenEndpoint,
        audience,
        sourceCredentialsFileLocation,
        getFromServiceLoader(HttpTransportFactory.class, defaultHttpTransportFactory));
  }

  @VisibleForTesting
  static StsCredentials create(
      String identityTokenEndpoint,
      String audience,
      String sourceCredentialsFileLocation,
      HttpTransportFactory transportFactory) {
    return new StsCredentials(
        identityTokenEndpoint, audience, sourceCredentialsFileLocation, transportFactory);
  }

  @Override
  public AccessToken refreshAccessToken() throws IOException {
    AccessToken tok = getSourceAccessTokenFromFileLocation();

    HttpTransport httpTransport = this.transportFactory.create();
    JsonObjectParser parser = new JsonObjectParser(JacksonFactory.getDefaultInstance());

    HttpRequestFactory requestFactory = httpTransport.createRequestFactory();
    GenericUrl url = new GenericUrl(identityTokenEndpoint);

    Map<String, String> params = new HashMap<>();
    params.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
    params.put("subject_token_type", "urn:ietf:params:oauth:token-type:jwt");
    params.put("requested_token_type", "urn:ietf:params:oauth:token-type:access_token");
    params.put("subject_token", tok.getTokenValue());
    params.put("scope", CLOUD_PLATFORM_SCOPE);
    params.put("audience", audience);
    HttpContent content = new JsonHttpContent(parser.getJsonFactory(), params);
    HttpRequest request = requestFactory.buildPostRequest(url, content);
    request.setParser(parser);

    HttpResponse response = null;
    try {
      response = request.execute();
    } catch (IOException e) {
      throw new IOException("Error requesting access token", e);
    }

    if (response.getStatusCode() != HttpStatusCodes.STATUS_CODE_OK) {
      throw new IOException("Error getting access token " + getStatusString(response));
    }

    GenericData responseData = response.parseAs(GenericData.class);
    response.disconnect();

    String access_token = (String) responseData.get("access_token");
    Date expiryTime = null;  // just in case expired_in value is not present
    if (responseData.containsKey("expires_in")) {
      expiryTime = new Date(System.currentTimeMillis()
        + ((BigDecimal) responseData.get("expires_in")).longValue() * 1000L);
    }
    return new AccessToken(access_token, expiryTime);
  }

  private AccessToken getSourceAccessTokenFromFileLocation() throws IOException {
    return new AccessToken(
        Files.asCharSource(new File(sourceCredentialsFileLocation), StandardCharsets.UTF_8).read(),
        null);
  }

  private static String getStatusString(HttpResponse response) {
    return response.getStatusCode() + " : " + response.getStatusMessage();
  }

  @Override
  public Builder toBuilder() {
    throw new UnsupportedOperationException("toBuilder not supported");
  }

  private static class DefaultHttpTransportFactory implements HttpTransportFactory {

    @Override
    public HttpTransport create() {
      return netHttpTransport;
    }
  }
}
