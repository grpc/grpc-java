/*
 * Copyright 2025 The gRPC Authors
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;


/** Unit tests for {@link JwtTokenFileXdsCallCredentialsProvider}. */
@RunWith(JUnit4.class)
public class JwtTokenFileXdsCallCredentialsProviderTest {
  private JwtTokenFileXdsCallCredentialsProvider provider =
      new JwtTokenFileXdsCallCredentialsProvider();

  @Test
  public void callCredentialsWhenNullConfig() {
    assertNull(provider.newCallCredentials(null));
  }

  @Test
  public void callCredentialsWhenWrongConfig() {
    Map<String, ?> jsonConfig = ImmutableMap.of("not_expected_config_key", "some_value");
    assertNull(provider.newCallCredentials(jsonConfig));
  }

  @Test
  public void callCredentialsWhenExpectedConfig() throws Exception {
    Map<String, ?> jsonConfig = ImmutableMap.of("jwt_token_file", "/path/to/jwt.token");
    assertEquals("io.grpc.auth.GoogleAuthLibraryCallCredentials",
        provider.newCallCredentials(jsonConfig).getClass().getName());
  }
}
