/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.xds.internal.matcher;

/**
 * Interface for resolving attributes from {@link MatchContext}.
 */
public interface AttributeProvider {
  /**
   * Returns the names of the attributes this provider handles.
   */
  Iterable<String> names();

  /**
   * Resolves the attribute value from the context.
   */
  Object get(MatchContext context);
}
