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

package io.grpc.observability;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpTransportFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Scanner;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A container of all global custom tags used for logging (for now). */
final class GlobalLoggingTags {
  private static final Logger logger = Logger.getLogger(GlobalLoggingTags.class.getName());

  private static final String ENV_KEY_PREFIX = "GRPC_OBSERVABILITY_";
  private final Map<String, String> tags;

  GlobalLoggingTags() {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    populate(builder);
    tags = builder.build();
  }

  Map<String, String> getTags() {
    return tags;
  }

  @VisibleForTesting
  static void populateFromMetadataServer(ImmutableMap.Builder<String, String> customTags) {
    MetadataConfig metadataConfig = new MetadataConfig(new DefaultHttpTransportFactory());
    metadataConfig.init();
    customTags.putAll(metadataConfig.getAllValues());
  }

  @VisibleForTesting
  static void populateFromKubernetesValues(ImmutableMap.Builder<String, String> customTags,
      String namespaceFile,
      String hostnameFile, String cgroupFile) {
    // namespace name: contents of file /var/run/secrets/kubernetes.io/serviceaccount/namespace
    populateFromFileContents(customTags, "namespace_name",
        namespaceFile, (value) -> value);

    // pod_name: hostname i.e. contents of /etc/hostname
    populateFromFileContents(customTags, "pod_name", hostnameFile, (value) -> value);

    // container_id: parsed from /proc/self/cgroup . Note: only works for Linux-based containers
    populateFromFileContents(customTags, "container_id", cgroupFile,
        (value) -> getContainerIdFromFileContents(value));
  }

  @VisibleForTesting
  static void populateFromFileContents(ImmutableMap.Builder<String, String> customTags, String key,
      String filePath, Function<String, String> parser) {
    String value = parser.apply(readFileContents(filePath));
    if (value != null) {
      customTags.put(key, value);
    }
  }

  /**
   * Parse from a line such as this.
   * 1:name=systemd:/kubepods/burstable/podf5143dd2/de67c4419b20924eaa141813
   *
   * @param value file contents
   * @return container-id parsed ("podf5143dd2/de67c4419b20924eaa141813" from the above snippet)
   */
  @VisibleForTesting static String getContainerIdFromFileContents(String value) {
    if (value != null) {
      try (Scanner scanner = new Scanner(value)) {
        while (scanner.hasNextLine()) {
          String line = scanner.nextLine();
          String[] tokens = line.split(":");
          if (tokens.length == 3 && tokens[2].startsWith("/kubepods/burstable/")) {
            tokens = tokens[2].split("/");
            if (tokens.length == 5) {
              return tokens[4];
            }
          }
        }
      }
    }
    return null;
  }

  private static String readFileContents(String file) {
    Path fileName = Paths.get(file);
    if (Files.isReadable(fileName)) {
      try {
        byte[] bytes = Files.readAllBytes(fileName);
        return new String(bytes, Charsets.US_ASCII);
      } catch (IOException e) {
        logger.log(Level.FINE, "Reading file:" + file, e);
      }
    } else {
      logger.log(Level.FINE, "File:" + file + " is not readable (or missing?)");
    }
    return null;
  }

  private static void populateFromEnvironmentVars(ImmutableMap.Builder<String, String> customTags) {
    populateFromMap(System.getenv(), customTags);
  }

  @VisibleForTesting
  static void populateFromMap(Map<String, String> map,
      final ImmutableMap.Builder<String, String> customTags) {
    checkNotNull(map);
    map.forEach((k, v) -> {
      if (k.startsWith(ENV_KEY_PREFIX)) {
        String customTagKey = k.substring(ENV_KEY_PREFIX.length());
        customTags.put(customTagKey, v);
      }
    });
  }

  static void populate(ImmutableMap.Builder<String, String> customTags) {
    populateFromEnvironmentVars(customTags);
    populateFromMetadataServer(customTags);
    populateFromKubernetesValues(customTags,
        "/var/run/secrets/kubernetes.io/serviceaccount/namespace",
        "/etc/hostname", "/proc/self/cgroup");
  }

  private static class DefaultHttpTransportFactory implements HttpTransportFactory {

    private static final HttpTransport netHttpTransport = new NetHttpTransport();

    @Override
    public HttpTransport create() {
      return netHttpTransport;
    }
  }
}
