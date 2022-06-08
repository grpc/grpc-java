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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;


@RunWith(JUnit4.class)
public class AuthorizationServerInterceptorTest {
  @Test
  public void invalidPolicyFailsStaticAuthzInterceptorCreation() throws Exception {
    String policy = "{ \"name\": \"abc\",, }";
    try {
      AuthorizationServerInterceptor.create(policy);
      fail("exception expected");
    } catch (IOException ioe) {
      assertThat(ioe).hasMessageThat().isEqualTo(
          "Use JsonReader.setLenient(true) to accept malformed JSON"
          + " at line 1 column 18 path $.name");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void validPolicyCreatesStaticAuthzInterceptor() throws Exception {
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
    assertNotNull(AuthorizationServerInterceptor.create(policy));
  }
}
