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

import com.google.common.io.BaseEncoding;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.rules.TemporaryFolder;

public class JwtTokenFileTestUtils {
  public static File createEmptyJwtToken(TemporaryFolder tempFolder) throws Exception {
    File jwtToken = tempFolder.newFile(new String("jwt.token"));
    return jwtToken;
  }

  public static File createJwtTokenWithoutExpiration(TemporaryFolder tempFolder) throws Exception {
    File jwtToken = tempFolder.newFile(new String("jwt.token"));
    FileOutputStream outputStream = new FileOutputStream(jwtToken);
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
    return jwtToken;
  }

  public static File createValidJwtToken(TemporaryFolder tempFolder, Long expTime)
        throws Exception {
    File jwtToken = tempFolder.newFile(new String("jwt.token"));
    FileOutputStream outputStream = new FileOutputStream(jwtToken);
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
    return jwtToken;
  }
}
