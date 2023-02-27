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
import java.io.FileOutputStream;
import java.io.IOException;
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
    try (FileOutputStream outputStream = new FileOutputStream(policyFile)) {
      String policy = "{ \"name\": \"abc\",, }";
      outputStream.write(policy.getBytes(UTF_8));
      outputStream.close();
    }
    try {
      FileWatcherAuthorizationServerInterceptor.create(policyFile);
      fail("exception expected");
    } catch (IOException ioe) {
      assertThat(ioe).hasMessageThat().contains("malformed");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void validPolicyCreatesFileWatcherAuthzInterceptor() throws Exception {
    File policyFile = File.createTempFile("temp", "json");
    policyFile.deleteOnExit();
    try (FileOutputStream outputStream = new FileOutputStream(policyFile)) {
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
      outputStream.write(policy.getBytes(UTF_8));
      outputStream.close();
    }
    FileWatcherAuthorizationServerInterceptor interceptor = 
        FileWatcherAuthorizationServerInterceptor.create(policyFile);
    assertNotNull(interceptor);
  }

  @Test
  public void invalidRefreshIntervalFailsScheduleRefreshes() throws Exception {
    File policyFile = File.createTempFile("temp", "json");
    policyFile.deleteOnExit();
    try (FileOutputStream outputStream = new FileOutputStream(policyFile)) {
      String policy = "{"
          + " \"name\" : \"authz\","
          + " \"allow_rules\": ["
          + "   {"
          + "     \"name\": \"allow_all\""
          + "   }"
          + " ]"
          + "}";
      outputStream.write(policy.getBytes(UTF_8));
      outputStream.close();
    }
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
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }
}
