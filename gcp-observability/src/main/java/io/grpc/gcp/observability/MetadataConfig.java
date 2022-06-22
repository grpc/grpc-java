/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.gcp.observability;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpStatusCodes;
import com.google.api.client.http.HttpTransport;
import com.google.auth.http.HttpTransportFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Class to read Google Metadata Server values. */
final class MetadataConfig {
  private static final Logger logger = Logger.getLogger(MetadataConfig.class.getName());

  private static final int TIMEOUT_MS = 5000;
  private static final String METADATA_URL = "http://metadata.google.internal/computeMetadata/v1/";
  private HttpRequestFactory requestFactory;
  private HttpTransportFactory transportFactory;

  @VisibleForTesting public MetadataConfig(HttpTransportFactory transportFactory) {
    this.transportFactory = transportFactory;

  }

  void init() {
    HttpTransport httpTransport = transportFactory.create();
    requestFactory = httpTransport.createRequestFactory();
  }

  /** gets all the values from the MDS we need to set in our logging tags. */
  ImmutableMap<String, String> getAllValues() {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    //addValueFor(builder, "instance/hostname", "GCE_INSTANCE_HOSTNAME");
    addValueFor(builder, "instance/id", "gke_node_id");
    //addValueFor(builder, "instance/zone", "GCE_INSTANCE_ZONE");
    addValueFor(builder, "project/project-id", "project_id");
    addValueFor(builder, "project/numeric-project-id", "project_numeric_id");
    addValueFor(builder, "instance/attributes/cluster-name", "cluster_name");
    addValueFor(builder, "instance/attributes/cluster-uid", "cluster_uid");
    addValueFor(builder, "instance/attributes/cluster-location", "location");
    try {
      requestFactory.getTransport().shutdown();
    } catch (IOException e) {
      logger.log(Level.FINE, "Calling HttpTransport.shutdown()", e);
    }
    return builder.buildOrThrow();
  }

  void addValueFor(ImmutableMap.Builder<String, String> builder, String attribute, String key) {
    try {
      String value = getAttribute(attribute);
      if (value != null) {
        builder.put(key, value);
      }
    } catch (IOException e) {
      logger.log(Level.FINE, "Calling getAttribute('" + attribute +  "')", e);
    }
  }

  String getAttribute(String attributeName) throws IOException {
    GenericUrl url = new GenericUrl(METADATA_URL + attributeName);
    HttpRequest request = requestFactory.buildGetRequest(url);
    request = request.setReadTimeout(TIMEOUT_MS);
    request = request.setConnectTimeout(TIMEOUT_MS);
    request = request.setHeaders(new HttpHeaders().set("Metadata-Flavor", "Google"));
    HttpResponse response = null;
    try {
      response = request.execute();
      if (response.getStatusCode() == HttpStatusCodes.STATUS_CODE_OK) {
        InputStream stream = response.getContent();
        if (stream != null) {
          byte[] bytes = new byte[stream.available()];
          stream.read(bytes);
          return new String(bytes, response.getContentCharset());
        }
      }
    } finally {
      if (response != null) {
        response.disconnect();
      }
    }
    return null;
  }
}
