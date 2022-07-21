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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GlobalLocationTagsTest {
  private static String FILE_CONTENTS =
      "12:perf_event:/kubepods/burstable/podc43b6442-0725-4fb8-bb1c-d17f5122155c/"
          + "fe61ca6482b58f4a9831d08d6ea15db25f9fd19b4be19a54df8c6c0eab8742b7\n"
          + "11:freezer:/kubepods/burstable/podc43b6442-0725-4fb8-bb1c-d17f5122155c/"
          + "fe61ca6482b58f4a9831d08d6ea15db25f9fd19b4be19a54df8c6c0eab8742b7\n"
          + "2:rdma:/\n"
          + "1:name=systemd:/kubepods/burstable/podc43b6442-0725-4fb8-bb1c-d17f5122155c/"
          + "fe61ca6482b58f4a9831d08d6ea15db25f9fd19b4be19a54df8c6c0eab8742b7\n"
          + "0::/system.slice/containerd.service\n";

  private static String FILE_CONTENTS_LAST_LINE =
      "0::/system.slice/containerd.service\n"
          + "6442-0725-4fb8-bb1c-d17f5122155cslslsl/fe61ca6482b58f4a9831d08d6ea15db25f\n"
          + "\n"
          + "12:perf_event:/kubepods/burstable/podc43b6442-0725-4fb8-bb1c-d17f5122155c/e19a54df\n";

  @Rule public TemporaryFolder namespaceFolder = new TemporaryFolder();
  @Rule public TemporaryFolder hostnameFolder = new TemporaryFolder();
  @Rule public TemporaryFolder cgroupFolder = new TemporaryFolder();

  @Test
  public void testContainerIdParsing_lastLine() {
    String containerId = GlobalLocationTags.getContainerIdFromFileContents(FILE_CONTENTS_LAST_LINE);
    assertThat(containerId).isEqualTo("e19a54df");
  }

  @Test
  public void testContainerIdParsing_fewerFields_notFound() {
    String containerId = GlobalLocationTags.getContainerIdFromFileContents(
        "12:/kubepods/burstable/podc43b6442-0725-4fb8-bb1c-d17f5122155c/"
            + "fe61ca6482b58f4a9831d08d6ea15db25f9fd19b4be19a54df8c6c0eab8742b7\n");
    assertThat(containerId).isNull();
  }

  @Test
  public void testContainerIdParsing_fewerPaths_notFound() {
    String containerId = GlobalLocationTags.getContainerIdFromFileContents(
        "12:xdf:/kubepods/podc43b6442-0725-4fb8-bb1c-d17f5122155c/"
            + "fe61ca6482b58f4a9831d08d6ea15db25f9fd19b4be19a54df8c6c0eab8742b7\n");
    assertThat(containerId).isNull();
  }

  @Test
  public void testPopulateKubernetesValues() throws IOException {
    File namespaceFile = namespaceFolder.newFile();
    File hostnameFile = hostnameFolder.newFile();
    File cgroupFile = cgroupFolder.newFile();

    Files.write("test-namespace1".getBytes(StandardCharsets.UTF_8), namespaceFile);
    Files.write("test-hostname2\n".getBytes(StandardCharsets.UTF_8), hostnameFile);
    Files.write(FILE_CONTENTS.getBytes(StandardCharsets.UTF_8), cgroupFile);

    ImmutableMap.Builder<String, String> locationTags = ImmutableMap.builder();
    GlobalLocationTags.populateFromKubernetesValues(locationTags, namespaceFile.getAbsolutePath(),
        hostnameFile.getAbsolutePath(), cgroupFile.getAbsolutePath());
    assertThat(locationTags.buildOrThrow()).containsExactly("container_id",
        "fe61ca6482b58f4a9831d08d6ea15db25f9fd19b4be19a54df8c6c0eab8742b7", "namespace_name",
        "test-namespace1", "pod_name", "test-hostname2");
  }

  @Test
  public void testNonKubernetesInstanceValues() throws IOException {
    String namespaceFilePath = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";
    File hostnameFile = hostnameFolder.newFile();
    File cgroupFile = cgroupFolder.newFile();

    Files.write("test-hostname2\n".getBytes(StandardCharsets.UTF_8), hostnameFile);
    Files.write(FILE_CONTENTS.getBytes(StandardCharsets.UTF_8), cgroupFile);

    ImmutableMap.Builder<String, String> locationTags = ImmutableMap.builder();
    GlobalLocationTags.populateFromKubernetesValues(locationTags,
        namespaceFilePath, hostnameFile.getAbsolutePath(), cgroupFile.getAbsolutePath());
    assertThat(locationTags.buildOrThrow()).containsExactly("container_id",
        "fe61ca6482b58f4a9831d08d6ea15db25f9fd19b4be19a54df8c6c0eab8742b7",
        "pod_name", "test-hostname2");
  }
}
