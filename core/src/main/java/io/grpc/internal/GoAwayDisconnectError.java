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

package io.grpc.internal;


import javax.annotation.concurrent.Immutable;

/**
 * Represents a dynamic disconnection due to an HTTP/2 GOAWAY frame.
 * This class is immutable and holds the specific error code from the frame.
 */
@Immutable
public final class GoAwayDisconnectError implements DisconnectError {
  private static final String ERROR_TAG = "GOAWAY";
  private final GrpcUtil.Http2Error errorCode;

  /**
   * Creates a GoAway reason.
   *
   * @param errorCode The specific, non-null HTTP/2 error code (e.g., "NO_ERROR").
   */
  public GoAwayDisconnectError(GrpcUtil.Http2Error errorCode) {
    if (errorCode == null) {
      throw new NullPointerException("Http2Error cannot be null for GOAWAY");
    }
    this.errorCode = errorCode;
  }

  @Override
  public String toErrorString() {
    return ERROR_TAG + " " + errorCode.name();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    GoAwayDisconnectError goAwayDisconnectError = (GoAwayDisconnectError) o;
    return errorCode == goAwayDisconnectError.errorCode;
  }

  @Override
  public int hashCode() {
    return errorCode.hashCode();
  }
}
