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

package io.grpc.xds.internal.rbac.engine.cel;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import javax.annotation.Nullable;
  
/**
 * Binds names to values.
 * 
 * <p>This is a Java stub for evaluating Common Expression Language (CEL). 
 * More information about CEL can be found in https://github.com/google/cel-spec. 
 * Once Java CEL has been open-sourced, this stub will be removed.
 */
public abstract class Activation {
  /** Resolves the given name to its value. Returns null if resolution fails. */
  @Nullable
  public abstract Object resolve(String name);

  /** Creates an immutable binder using the provided mapping. */
  public static Activation copyOf(Map<String, ?> map) {
    final ImmutableMap<String, Object> copy = ImmutableMap.copyOf(map);
    return new Activation() {
      @Nullable
      @Override
      public Object resolve(String name) {
        return copy.get(name);
      }

      @Override
      public String toString() {
        return copy.toString();
      }
    };
  }
}
