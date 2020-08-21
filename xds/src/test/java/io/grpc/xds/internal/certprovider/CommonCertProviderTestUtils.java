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

package io.grpc.xds.internal.certprovider;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.CharStreams;
import io.grpc.internal.testing.TestUtils;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.internal.sds.trust.CertificateUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.util.CharsetUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.security.KeyException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommonCertProviderTestUtils {
  private static final Logger logger =
          Logger.getLogger(CommonCertProviderTestUtils.class.getName());

  private static final Pattern KEY_PATTERN = Pattern.compile(
          "-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + // Header
                  "([a-z0-9+/=\\r\\n]+)" +                       // Base64 text
                  "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+",            // Footer
          Pattern.CASE_INSENSITIVE);

  static Bootstrapper.BootstrapInfo getTestBootstrapInfo() throws IOException {
    String rawData =
        "{\n"
            + "  \"xds_servers\": [],\n"
            + "  \"certificate_providers\": {\n"
            + "    \"gcp_id\": {\n"
            + "      \"plugin_name\": \"testca\",\n"
            + "      \"config\": {\n"
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
            + "      }\n" // end config
            + "    },\n" // end gcp_id
            + "    \"file_provider\": {\n"
            + "      \"plugin_name\": \"file_watcher\",\n"
            + "      \"config\": {\"path\": \"/etc/secret/certs\"}\n"
            + "    }\n"
            + "  }\n"
            + "}";
    return Bootstrapper.parseConfig(rawData);
  }

  static Bootstrapper.BootstrapInfo getNonDefaultTestBootstrapInfo() throws IOException {
    String rawData =
        "{\n"
            + "  \"xds_servers\": [],\n"
            + "  \"certificate_providers\": {\n"
            + "    \"gcp_id\": {\n"
            + "      \"plugin_name\": \"testca\",\n"
            + "      \"config\": {\n"
            + "        \"server\": {\n"
            + "          \"api_type\": \"GRPC\",\n"
            + "          \"grpc_services\": [{\n"
            + "            \"google_grpc\": {\n"
            + "              \"target_uri\": \"nonDefaultMeshCaUrl\",\n"
            + "              \"channel_credentials\": {\"google_default\": {}},\n"
            + "              \"call_credentials\": [{\n"
            + "                \"sts_service\": {\n"
            + "                  \"token_exchange_service\": \"test.sts.com\",\n"
            + "                  \"subject_token_path\": \"/tmp/path4\"\n"
            + "                }\n"
            + "              }]\n" // end call_credentials
            + "            },\n" // end google_grpc
            + "            \"time_out\": {\"seconds\": 12}\n"
            + "          }]\n" // end grpc_services
            + "        },\n" // end server
            + "        \"certificate_lifetime\": {\"seconds\": 234567},\n"
            + "        \"renewal_grace_period\": {\"seconds\": 4321},\n"
            + "        \"key_type\": \"RSA\",\n"
            + "        \"key_size\": 512,\n"
            + "        \"location\": \"https://container.googleapis.com/v1/projects/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
            + "      }\n" // end config
            + "    },\n" // end gcp_id
            + "    \"file_provider\": {\n"
            + "      \"plugin_name\": \"file_watcher\",\n"
            + "      \"config\": {\"path\": \"/etc/secret/certs\"}\n"
            + "    }\n"
            + "  }\n"
            + "}";
    return Bootstrapper.parseConfig(rawData);
  }

  static Bootstrapper.BootstrapInfo getMinimalBootstrapInfo() throws IOException {
    String rawData =
        "{\n"
            + "  \"xds_servers\": [],\n"
            + "  \"certificate_providers\": {\n"
            + "    \"gcp_id\": {\n"
            + "      \"plugin_name\": \"testca\",\n"
            + "      \"config\": {\n"
            + "        \"server\": {\n"
            + "          \"api_type\": \"GRPC\",\n"
            + "          \"grpc_services\": [{\n"
            + "            \"google_grpc\": {\n"
            + "              \"call_credentials\": [{\n"
            + "                \"sts_service\": {\n"
            + "                  \"subject_token_path\": \"/tmp/path5\"\n"
            + "                }\n"
            + "              }]\n" // end call_credentials
            + "            }\n" // end google_grpc
            + "          }]\n" // end grpc_services
            + "        },\n" // end server
            + "        \"location\": \"https://container.googleapis.com/v1/projects/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
            + "      }\n" // end config
            + "    }\n" // end gcp_id
            + "  }\n"
            + "}";
    return Bootstrapper.parseConfig(rawData);
  }

  static Bootstrapper.BootstrapInfo getMinimalAndBadClusterUrlBootstrapInfo() throws IOException {
    String rawData =
            "{\n"
                    + "  \"xds_servers\": [],\n"
                    + "  \"certificate_providers\": {\n"
                    + "    \"gcp_id\": {\n"
                    + "      \"plugin_name\": \"testca\",\n"
                    + "      \"config\": {\n"
                    + "        \"server\": {\n"
                    + "          \"api_type\": \"GRPC\",\n"
                    + "          \"grpc_services\": [{\n"
                    + "            \"google_grpc\": {\n"
                    + "              \"call_credentials\": [{\n"
                    + "                \"sts_service\": {\n"
                    + "                  \"subject_token_path\": \"/tmp/path5\"\n"
                    + "                }\n"
                    + "              }]\n" // end call_credentials
                    + "            }\n" // end google_grpc
                    + "          }]\n" // end grpc_services
                    + "        },\n" // end server
                    + "        \"location\": \"https://container.googleapis.com/v1/project/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
                    + "      }\n" // end config
                    + "    }\n" // end gcp_id
                    + "  }\n"
                    + "}";
    return Bootstrapper.parseConfig(rawData);
  }

  static Bootstrapper.BootstrapInfo getMissingSaJwtLocation() throws IOException {
    String rawData =
        "{\n"
            + "  \"xds_servers\": [],\n"
            + "  \"certificate_providers\": {\n"
            + "    \"gcp_id\": {\n"
            + "      \"plugin_name\": \"testca\",\n"
            + "      \"config\": {\n"
            + "        \"server\": {\n"
            + "          \"api_type\": \"GRPC\",\n"
            + "          \"grpc_services\": [{\n"
            + "            \"google_grpc\": {\n"
            + "              \"call_credentials\": [{\n"
            + "                \"sts_service\": {\n"
            + "                }\n"
            + "              }]\n" // end call_credentials
            + "            }\n" // end google_grpc
            + "          }]\n" // end grpc_services
            + "        },\n" // end server
            + "        \"location\": \"https://container.googleapis.com/v1/projects/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
            + "      }\n" // end config
            + "    }\n" // end gcp_id
            + "  }\n"
            + "}";
    return Bootstrapper.parseConfig(rawData);
  }

  static Bootstrapper.BootstrapInfo getMissingGkeClusterUrl() throws IOException {
    String rawData =
        "{\n"
            + "  \"xds_servers\": [],\n"
            + "  \"certificate_providers\": {\n"
            + "    \"gcp_id\": {\n"
            + "      \"plugin_name\": \"testca\",\n"
            + "      \"config\": {\n"
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
            + "        \"key_size\": 2048\n"
            + "      }\n" // end config
            + "    },\n" // end gcp_id
            + "    \"file_provider\": {\n"
            + "      \"plugin_name\": \"file_watcher\",\n"
            + "      \"config\": {\"path\": \"/etc/secret/certs\"}\n"
            + "    }\n"
            + "  }\n"
            + "}";
    return Bootstrapper.parseConfig(rawData);
  }

  static Bootstrapper.BootstrapInfo getBadChannelCredsConfig() throws IOException {
    String rawData =
        "{\n"
            + "  \"xds_servers\": [],\n"
            + "  \"certificate_providers\": {\n"
            + "    \"gcp_id\": {\n"
            + "      \"plugin_name\": \"testca\",\n"
            + "      \"config\": {\n"
            + "        \"server\": {\n"
            + "          \"api_type\": \"GRPC\",\n"
            + "          \"grpc_services\": [{\n"
            + "            \"google_grpc\": {\n"
            + "              \"channel_credentials\": {\"mtls\": \"true\"},\n"
            + "              \"call_credentials\": [{\n"
            + "                \"sts_service\": {\n"
            + "                  \"subject_token_path\": \"/tmp/path5\"\n"
            + "                }\n"
            + "              }]\n" // end call_credentials
            + "            }\n" // end google_grpc
            + "          }]\n" // end grpc_services
            + "        },\n" // end server
            + "        \"location\": \"https://container.googleapis.com/v1/projects/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
            + "      }\n" // end config
            + "    }\n" // end gcp_id
            + "  }\n"
            + "}";
    return Bootstrapper.parseConfig(rawData);
  }

  static PrivateKey getPrivateKey(String resourceName)
          throws Exception {
    InputStream inputStream = TestUtils.class.getResourceAsStream("/certs/" + resourceName);
    ByteBuf encodedKeyBuf = readPrivateKey(inputStream);

    byte[] encodedKey = new byte[encodedKeyBuf.readableBytes()];
    encodedKeyBuf.readBytes(encodedKey).release();
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(encodedKey);
    return KeyFactory.getInstance("RSA").generatePrivate(spec);
  }

  static ByteBuf readPrivateKey(InputStream in) throws KeyException {
    String content;
    try {
      content = readContent(in);
    } catch (IOException e) {
      throw new KeyException("failed to read key input stream", e);
    }
    Matcher m = KEY_PATTERN.matcher(content);
    if (!m.find()) {
      throw new KeyException("could not find a PKCS #8 private key in input stream");
    }
    ByteBuf base64 = Unpooled.copiedBuffer(m.group(1), CharsetUtil.US_ASCII);
    ByteBuf der = Base64.decode(base64);
    base64.release();
    return der;
  }

  private static String readContent(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      byte[] buf = new byte[8192];
      for (; ; ) {
        int ret = in.read(buf);
        if (ret < 0) {
          break;
        }
        out.write(buf, 0, ret);
      }
      return out.toString(CharsetUtil.US_ASCII.name());
    } finally {
      safeClose(out);
    }
  }

  private static void safeClose(OutputStream out) {
    try {
      out.close();
    } catch (IOException e) {
      logger.log(Level.WARNING, "Failed to close a stream.", e);
    }
  }

  static X509Certificate getCertFromResourceName(String resourceName)
          throws IOException, CertificateException {
    return CertificateUtils.toX509Certificate(
            new ByteArrayInputStream(getResourceContents(resourceName).getBytes(UTF_8)));
  }

  private static String getResourceContents(String resourceName) throws IOException {
    InputStream inputStream = TestUtils.class.getResourceAsStream("/certs/" + resourceName);
    String text = null;
    try (Reader reader = new InputStreamReader(inputStream, UTF_8)) {
      text = CharStreams.toString(reader);
    }
    return text;
  }
}
