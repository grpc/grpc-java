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

package io.grpc.alts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.auth.oauth2.AccessToken;
import com.google.common.io.BaseEncoding;
import com.google.common.truth.Truth;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link JwtTokenFileCallCredentials}. */
@RunWith(JUnit4.class)
public class JwtTokenFileCallCredentialsTest {
  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private File jwtTokenFile;
  private JwtTokenFileCallCredentials unit;

  @Before
  public void setUp() throws Exception {
    jwtTokenFile = tempFolder.newFile(new String("jwt.json"));

    Constructor<JwtTokenFileCallCredentials> ctor =
        JwtTokenFileCallCredentials.class.getDeclaredConstructor(String.class);
    ctor.setAccessible(true);
    unit = ctor.newInstance(jwtTokenFile.toString());
  }

  private void fillJwtTokenWithoutExpiration(File jwtFile) throws Exception {
    FileOutputStream outputStream = new FileOutputStream(jwtFile);
    String content =
        BaseEncoding.base64().encode(
            new String("{\"typ\": \"JWT\", \"alg\": \"HS256\"}").getBytes(StandardCharsets.UTF_8))
        + "."
        + BaseEncoding.base64().encode(
            new String("{\"name\": \"Google\"}").getBytes(StandardCharsets.UTF_8))
        + "."
        + BaseEncoding.base64().encode(new String("signature").getBytes(StandardCharsets.UTF_8));
    outputStream.write(content.getBytes(StandardCharsets.UTF_8));
    outputStream.close();
  }

  private String fillValidJwtToken(File jwtFile, Long expTime) throws Exception {
    FileOutputStream outputStream = new FileOutputStream(jwtFile);
    String content =
        BaseEncoding.base64().encode(
            new String("{\"typ\": \"JWT\", \"alg\": \"HS256\"}").getBytes(StandardCharsets.UTF_8))
        + "."
        + BaseEncoding.base64().encode(
            String.format("{\"exp\": %d}", expTime).getBytes(StandardCharsets.UTF_8))
        + "."
        + BaseEncoding.base64().encode(new String("signature").getBytes(StandardCharsets.UTF_8));
    outputStream.write(content.getBytes(StandardCharsets.UTF_8));
    outputStream.close();
    return content;
  }

  @Test
  public void givenJwtTokenFileEmpty_WhenTokenRefreshed_ExpectException() {
    assertThrows(IllegalArgumentException.class, () -> {
      unit.refreshAccessToken();
    });
  }

  @Test
  public void givenJwtTokenFileWithoutExpiration_WhenTokenRefreshed_ExpectException()
      throws Exception {

    fillJwtTokenWithoutExpiration(jwtTokenFile);

    Exception ex = assertThrows(IOException.class, () -> {
      unit.refreshAccessToken();
    });

    String expectedMsg = "No expiration time found for JWT token";
    String actualMsg = ex.getMessage();

    assertEquals(expectedMsg, actualMsg);
  }

  @Test
  public void givenValidJwtTokenFile_WhenTokenRefreshed_ExpectAccessTokenInstance()
      throws Exception {
    final Long givenExpTimeInSeconds = 1753364000L;
    final Date givenExpTimeDate = new Date(givenExpTimeInSeconds * 1000L);

    String givenTokenValue = fillValidJwtToken(jwtTokenFile, givenExpTimeInSeconds);

    AccessToken token = unit.refreshAccessToken();

    Truth.assertThat(token.getExpirationTime()).isEquivalentAccordingToCompareTo(givenExpTimeDate);
    assertEquals(token.getTokenValue(), givenTokenValue);
  }
}
