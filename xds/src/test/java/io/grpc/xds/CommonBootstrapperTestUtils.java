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
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.internal.security.CommonTlsContextTestsUtil;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

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
          Bootstrapper.CertificateProviderInfo.create(
              "testca", (Map<String, ?>) JsonParser.parse(MESHCA_CONFIG));
      Bootstrapper.CertificateProviderInfo fileProvider =
          Bootstrapper.CertificateProviderInfo.create(
              "file_watcher", (Map<String, ?>) JsonParser.parse(FILE_WATCHER_CONFIG));
      Map<String, Bootstrapper.CertificateProviderInfo> certProviders =
          ImmutableMap.of("gcp_id", gcpId, "file_provider", fileProvider);
      Bootstrapper.BootstrapInfo bootstrapInfo =
          Bootstrapper.BootstrapInfo.builder()
              .servers(ImmutableList.<Bootstrapper.ServerInfo>of())
              .node(EnvoyProtoData.Node.newBuilder().build())
              .certProviders(certProviders)
              .serverListenerResourceNameTemplate("grpc/server")
              .build();
      return bootstrapInfo;
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Build {@link Bootstrapper.BootstrapInfo} for certProviderInstance tests.
   * Populates with temp file paths.
   */
  public static Bootstrapper.BootstrapInfo buildBootstrapInfo(
      String certInstanceName1, @Nullable String privateKey1,
      @Nullable String cert1,
      @Nullable String trustCa1, String certInstanceName2, String privateKey2, String cert2,
      String trustCa2) {
    // get temp file for each file
    try {
      if (privateKey1 != null) {
        privateKey1 = CommonTlsContextTestsUtil.getTempFileNameForResourcesFile(privateKey1);
      }
      if (cert1 != null) {
        cert1 = CommonTlsContextTestsUtil.getTempFileNameForResourcesFile(cert1);
      }
      if (trustCa1 != null) {
        trustCa1 = CommonTlsContextTestsUtil.getTempFileNameForResourcesFile(trustCa1);
      }
      if (privateKey2 != null) {
        privateKey2 = CommonTlsContextTestsUtil.getTempFileNameForResourcesFile(privateKey2);
      }
      if (cert2 != null) {
        cert2 = CommonTlsContextTestsUtil.getTempFileNameForResourcesFile(cert2);
      }
      if (trustCa2 != null) {
        trustCa2 = CommonTlsContextTestsUtil.getTempFileNameForResourcesFile(trustCa2);
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
    HashMap<String, String> config = new HashMap<>();
    config.put("certificate_file", cert1);
    config.put("private_key_file", privateKey1);
    config.put("ca_certificate_file", trustCa1);
    Bootstrapper.CertificateProviderInfo certificateProviderInfo =
        Bootstrapper.CertificateProviderInfo.create("file_watcher", config);
    HashMap<String, Bootstrapper.CertificateProviderInfo> certProviders =
        new HashMap<>();
    certProviders.put(certInstanceName1, certificateProviderInfo);
    if (certInstanceName2 != null) {
      config = new HashMap<>();
      config.put("certificate_file", cert2);
      config.put("private_key_file", privateKey2);
      config.put("ca_certificate_file", trustCa2);
      certificateProviderInfo =
          Bootstrapper.CertificateProviderInfo.create("file_watcher", config);
      certProviders.put(certInstanceName2, certificateProviderInfo);
    }
    return Bootstrapper.BootstrapInfo.builder()
        .servers(ImmutableList.<ServerInfo>of())
        .node(EnvoyProtoData.Node.newBuilder().build())
        .certProviders(certProviders)
        .build();
  }
}
