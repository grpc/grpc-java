/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds.sds;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.isA;

import com.google.common.util.concurrent.MoreExecutors;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link TlsCertificateSecretVolumeSecretProvider}.
 */
@RunWith(JUnit4.class)
public class TlsCertificateSecretVolumeSecretProviderTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  /**
   * Utility function for creation of test files in a temp folder.
   *
   * @param temporaryFolder   temporary folder to use
   * @return  a config source representing the file based secret
   * @throws IOException  represents an IO exception
   */
  public static File createTestCertFiles(TemporaryFolder temporaryFolder) throws IOException {
    createATestCertFile(temporaryFolder, "mycert.pem", "pemContents");
    createATestCertFile(temporaryFolder, "mycert.crt", "crtContents");

    return new File(temporaryFolder.getRoot(), "mycert");
  }

  private static void createATestCertFile(TemporaryFolder temporaryFolder, String s,
      String pemContents) throws IOException {
    File pem = temporaryFolder.newFile(s);
    Writer pemFile = Files.newBufferedWriter(pem.toPath(), UTF_8);
    pemFile.write(pemContents);
    pemFile.close();
  }

  @Test
  public void readBothFiles() throws IOException, ExecutionException, InterruptedException {
    File filePath = createTestCertFiles(temporaryFolder);
    TlsCertificateSecretVolumeSecretProvider provider =
        new TlsCertificateSecretVolumeSecretProvider(filePath.getPath(), "test");

    TlsCertificateStore tlsCertificateStore = provider.get();
    assertThat(tlsCertificateStore).isNotNull();
    TlsCertificateStoreTest
        .verifyInputStreamAndString(tlsCertificateStore, "pemContents", "crtContents");
  }

  private boolean listenerRun;

  @Test
  public void verifyListenerExecuted()
      throws IOException, ExecutionException, InterruptedException {
    // use junk value for path since it is not used
    TlsCertificateSecretVolumeSecretProvider provider =
        new TlsCertificateSecretVolumeSecretProvider("/a/b/test", "test");

    listenerRun = false;
    provider.addListener(new Runnable() {

      @Override
      public void run() {
        listenerRun = true;
      }
    }, MoreExecutors.directExecutor());
    assertThat(listenerRun).isTrue();
  }

  @Test
  public void readMissingFile() throws IOException, ExecutionException, InterruptedException {
    createATestCertFile(temporaryFolder, "mycert.pem", "pemContents");

    // no crt file
    File filePath = new File(temporaryFolder.getRoot(), "mycert");
    TlsCertificateSecretVolumeSecretProvider provider =
        new TlsCertificateSecretVolumeSecretProvider(filePath.getPath(), "test");

    thrown.expect(ExecutionException.class);
    thrown.expectCause(isA(FileNotFoundException.class));
    provider.get();
  }

}
