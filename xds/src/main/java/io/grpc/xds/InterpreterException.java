/*
 * Copyright 2020 The gRPC Authors
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

import java.lang.Exception;
import javax.annotation.Nullable;

/** An exception produced during interpretation of expressions. */
public class InterpreterException extends Exception {
  /** Builder for InterpreterException. */
  public static class Builder {
    private final String message;
    @Nullable private String location;
    private int position;
    private Throwable cause;

    public Builder(String message, Object... args) {
      this.message = args.length > 0 ? String.format(message, args) : message;
    }

    /** Build function for InterpreterException. */
    public InterpreterException build() {
      return new InterpreterException(
          String.format(
              "evaluation error%s: %s",
              location != null ? " at " + location + ":" + position : "", message),
          cause);
    }
  }

  private InterpreterException(String message, Throwable cause) {
    super(message, cause);
  }
}