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

package io.grpc.xds.internal.extauthz;

/**
 * A custom exception for signaling errors during the parsing of external authorization
 * (ext_authz) configurations.
 */
public class ExtAuthzParseException extends Exception {

  private static final long serialVersionUID = 0L;

  public ExtAuthzParseException(String message) {
    super(message);
  }

  public ExtAuthzParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
