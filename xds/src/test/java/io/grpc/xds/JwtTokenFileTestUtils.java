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

import com.google.common.io.BaseEncoding;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class JwtTokenFileTestUtils {
  public static File createEmptyJwtToken() throws IOException {
    File jwtToken = File.createTempFile(new String("jwt.token"), "");
    jwtToken.deleteOnExit();
    return jwtToken;
  }

  public static File createJwtTokenWithoutExpiration() throws IOException {
    File jwtToken = File.createTempFile(new String("jwt.token"), "");
    jwtToken.deleteOnExit();
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

  public static File createValidJwtToken(long expTime)
        throws Exception {
    File jwtToken = File.createTempFile(new String("jwt.token"), "");
    jwtToken.deleteOnExit();
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
