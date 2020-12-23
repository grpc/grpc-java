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

import com.google.protobuf.Descriptors.Descriptor;

/**
 * An implementation of {@link RuntimeTypeProvider} which relies on proto descriptors.
 * 
 * <p>This is a Java stub for evaluating Common Expression Language (CEL). 
 * More information about CEL can be found in https://github.com/google/cel-spec. 
 * Once Java CEL has been open-sourced, this stub will be removed.
 */
public class DescriptorMessageProvider implements RuntimeTypeProvider {
  /**
   * Creates a new message provider that provides only {@link DynamicMessage DynamicMessages} for
   * the specified descriptors.
   */
  public static DescriptorMessageProvider dynamicMessages(Iterable<Descriptor> descriptors) {
    return new DescriptorMessageProvider();
  }
}
