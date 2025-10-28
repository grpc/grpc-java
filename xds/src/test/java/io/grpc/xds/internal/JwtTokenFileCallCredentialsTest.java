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

package io.grpc.xds.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.auth.oauth2.AccessToken;
import com.google.common.truth.Truth;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link JwtTokenFileCallCredentials}. */
@RunWith(Enclosed.class)
public class JwtTokenFileCallCredentialsTest {
  @RunWith(JUnit4.class)
  public static class WithEmptyJwtTokenTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File jwtTokenFile;
    private JwtTokenFileCallCredentials unit;

    @Before
    public void setUp() throws Exception {
      this.jwtTokenFile = tempFolder.newFile("empty_jwt.token");

      Constructor<JwtTokenFileCallCredentials> ctor =
          JwtTokenFileCallCredentials.class.getDeclaredConstructor(String.class);
      ctor.setAccessible(true);
      this.unit = ctor.newInstance(jwtTokenFile.toString());
    }

    @Test
    public void givenJwtTokenFileEmpty_WhenTokenRefreshed_ExpectException() {
      assertThrows(IllegalArgumentException.class, () -> {
        unit.refreshAccessToken();
      });
    }
  }

  @RunWith(JUnit4.class)
  public static class WithInvalidJwtTokenTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File jwtTokenFile;
    private JwtTokenFileCallCredentials unit;

    @Before
    public void setUp() throws Exception {
      this.jwtTokenFile = tempFolder.newFile("invalid_jwt.token");
      JwtTokenFileTestUtils.writeJwtTokenContentWithoutExpiration(jwtTokenFile);

      Constructor<JwtTokenFileCallCredentials> ctor =
          JwtTokenFileCallCredentials.class.getDeclaredConstructor(String.class);
      ctor.setAccessible(true);
      this.unit = ctor.newInstance(jwtTokenFile.toString());
    }

    @Test
    public void givenJwtTokenFileWithoutExpiration_WhenTokenRefreshed_ExpectException()
        throws Exception {
      Exception ex = assertThrows(IOException.class, () -> {
        unit.refreshAccessToken();
      });

      String expectedMsg = "No expiration time found for JWT token";
      String actualMsg = ex.getMessage();

      assertEquals(expectedMsg, actualMsg);
    }
  }

  @RunWith(JUnit4.class)
  public static class WithValidJwtTokenTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File jwtTokenFile;
    private JwtTokenFileCallCredentials unit;
    private Long givenExpTimeInSeconds;

    @Before
    public void setUp() throws Exception {
      this.jwtTokenFile = tempFolder.newFile("jwt.token");
      this.givenExpTimeInSeconds = Instant.now().getEpochSecond() + TimeUnit.HOURS.toSeconds(1);

      JwtTokenFileTestUtils.writeValidJwtTokenContent(jwtTokenFile, givenExpTimeInSeconds);

      Constructor<JwtTokenFileCallCredentials> ctor =
          JwtTokenFileCallCredentials.class.getDeclaredConstructor(String.class);
      ctor.setAccessible(true);
      this.unit = ctor.newInstance(jwtTokenFile.toString());
    }

    @Test
    public void givenValidJwtTokenFile_WhenTokenRefreshed_ExpectAccessTokenInstance()
        throws Exception {
      final Date givenExpTimeDate = new Date(TimeUnit.SECONDS.toMillis(givenExpTimeInSeconds));

      String givenTokenValue = new String(
          Files.readAllBytes(jwtTokenFile.toPath()),
          StandardCharsets.UTF_8);

      AccessToken token = unit.refreshAccessToken();

      Truth.assertThat(token.getExpirationTime())
          .isEquivalentAccordingToCompareTo(givenExpTimeDate);
      assertEquals(token.getTokenValue(), givenTokenValue);
    }
  }
}
