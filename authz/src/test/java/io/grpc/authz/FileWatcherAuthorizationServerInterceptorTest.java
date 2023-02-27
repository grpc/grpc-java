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

package io.grpc.authz;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class FileWatcherAuthorizationServerInterceptorTest {
  @Test
  public void invalidPolicyFailsAuthzInterceptorCreation() throws Exception {
    File policyFile = File.createTempFile("temp", "json");
    policyFile.deleteOnExit();
    String policy = "{ \"name\": \"abc\",, }";
    Files.write(Paths.get(policyFile.getAbsolutePath()), policy.getBytes(UTF_8));
    try {
      FileWatcherAuthorizationServerInterceptor.create(policyFile);
      fail("exception expected");
    } catch (IOException ioe) {
      assertThat(ioe).hasMessageThat().contains("malformed");
    }
  }

  @Test
  public void validPolicyCreatesFileWatcherAuthzInterceptor() throws Exception {
    File policyFile = File.createTempFile("temp", "json");
    policyFile.deleteOnExit();
    String policy = "{"
        + " \"name\" : \"authz\","
        + " \"deny_rules\": ["
        + "   {"
        + "     \"name\": \"deny_foo\","
        + "     \"source\": {"
        + "       \"principals\": ["
        + "         \"spiffe://foo.com\""
        + "       ]"
        + "     }"
        + "   }"
        + " ],"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_all\""
        + "   }"
        + " ]"
        + "}";
    Files.write(Paths.get(policyFile.getAbsolutePath()), policy.getBytes(UTF_8));
    FileWatcherAuthorizationServerInterceptor interceptor = 
        FileWatcherAuthorizationServerInterceptor.create(policyFile);
    assertNotNull(interceptor);
  }

  @Test
  public void invalidRefreshIntervalFailsScheduleRefreshes() throws Exception {
    File policyFile = File.createTempFile("temp", "json");
    policyFile.deleteOnExit();
    String policy = "{"
        + " \"name\" : \"authz\","
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_all\""
        + "   }"
        + " ]"
        + "}";
    Files.write(Paths.get(policyFile.getAbsolutePath()), policy.getBytes(UTF_8));
    FileWatcherAuthorizationServerInterceptor interceptor = 
        FileWatcherAuthorizationServerInterceptor.create(policyFile);
    assertNotNull(interceptor);
    try {
      interceptor.scheduleRefreshes(0, TimeUnit.SECONDS, 
          Executors.newSingleThreadScheduledExecutor());
      fail("exception expected");
    } catch (IllegalArgumentException iae) {
      assertThat(iae).hasMessageThat().isEqualTo(
          "Refresh interval must be greater than 0");
    }
  }
}
