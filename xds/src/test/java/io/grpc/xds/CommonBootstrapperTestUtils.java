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

package io.grpc.xds;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.internal.JsonParser;
import java.io.IOException;
import java.util.Map;

public class CommonBootstrapperTestUtils {
  private static final String FILE_WATCHER_CONFIG = "{\"path\": \"/etc/secret/certs\"}";
  private static final String MESHCA_CONFIG =
      "{\n"
          + "        \"server\": {\n"
          + "          \"api_type\": \"GRPC\",\n"
          + "          \"grpc_services\": [{\n"
          + "            \"google_grpc\": {\n"
          + "              \"target_uri\": \"meshca.com\",\n"
          + "              \"channel_credentials\": {\"google_default\": {}},\n"
          + "              \"call_credentials\": [{\n"
          + "                \"sts_service\": {\n"
          + "                  \"token_exchange_service\": \"securetoken.googleapis.com\",\n"
          + "                  \"subject_token_path\": \"/etc/secret/sajwt.token\"\n"
          + "                }\n"
          + "              }]\n" // end call_credentials
          + "            },\n" // end google_grpc
          + "            \"time_out\": {\"seconds\": 10}\n"
          + "          }]\n" // end grpc_services
          + "        },\n" // end server
          + "        \"certificate_lifetime\": {\"seconds\": 86400},\n"
          + "        \"renewal_grace_period\": {\"seconds\": 3600},\n"
          + "        \"key_type\": \"RSA\",\n"
          + "        \"key_size\": 2048,\n"
          + "        \"location\": \"https://container.googleapis.com/v1/project/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
          + "      }";

  /** Creates a test bootstrap info object. */
  @SuppressWarnings("unchecked")
  public static Bootstrapper.BootstrapInfo getTestBootstrapInfo() {
    try {
      Bootstrapper.CertificateProviderInfo gcpId =
          new Bootstrapper.CertificateProviderInfo(
              "testca", (Map<String, ?>) JsonParser.parse(MESHCA_CONFIG));
      Bootstrapper.CertificateProviderInfo fileProvider =
          new Bootstrapper.CertificateProviderInfo(
              "file_watcher", (Map<String, ?>) JsonParser.parse(FILE_WATCHER_CONFIG));
      Map<String, Bootstrapper.CertificateProviderInfo> certProviders =
          ImmutableMap.of("gcp_id", gcpId, "file_provider", fileProvider);
      Bootstrapper.BootstrapInfo bootstrapInfo =
          new Bootstrapper.BootstrapInfo(
              ImmutableList.<Bootstrapper.ServerInfo>of(),
              EnvoyProtoData.Node.newBuilder().build(),
              certProviders,
              "grpc/server");
      return bootstrapInfo;
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }
}
