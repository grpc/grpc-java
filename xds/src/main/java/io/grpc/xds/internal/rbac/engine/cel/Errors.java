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

package io.grpc.xds.internal;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import javax.annotation.Nullable;

/**
 * An object which manages error reporting. Enriches error messages by source context pointing to
 * the position in the source where the error occurred.
 */
public final class Errors {
  /**
   * Represents an error context. A context consists of a description (like a file name), and an
   * optional source. It is capable of calculating a line/column pair if the source is provided.
   */
  private static class Context {
    @SuppressWarnings("unused")
    private final String description;
    @SuppressWarnings("unused")
    @Nullable private final String source;

    private Context(String description, @Nullable String source) {
      this.description = description;
      this.source = source;
    }
  }

  private final List<Error> errors = new ArrayList<>();
  @SuppressWarnings("JdkObsolete")
  private final Stack<Context> context = new Stack<>();

  /**
   * Creates an errors object.
   *
   * @param description description of the root error context used in error messages.
   * @param source the (optional) source string associated with the root error context.
   */
  public Errors(String description, @Nullable String source) {
    enterContext(description, source);
  }

  /**
   * Enters a new error context. Errors reported in this context will use given description and
   * source.
   */
  public void enterContext(String description, @Nullable String source) {
    context.push(new Context(Preconditions.checkNotNull(description), source));
  }

  /** Returns the number of errors reported via this object. */
  public int getErrorCount() {
    return errors.size();
  }

  /** Returns a string will all errors reported via this object. */
  public String getAllErrorsAsString() {
    return Joiner.on(String.format("%n")).join(errors);
  }
}
