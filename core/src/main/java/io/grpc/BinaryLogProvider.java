/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc;

import java.util.Collections;
import javax.annotation.Nullable;

final class BinaryLogProvider {
  private static final BinaryLog PROVIDER = ServiceProviders.load(
      BinaryLog.class,
      Collections.<Class<?>>emptyList(),
      BinaryLogProvider.class.getClassLoader(),
      new ServiceProviders.PriorityAccessor<BinaryLog>() {
        @Override
        public boolean isAvailable(BinaryLog provider) {
          return provider.isAvailable();
        }

        @Override
        public int getPriority(BinaryLog provider) {
          return provider.priority();
        }
      });

  @Nullable
  public static BinaryLog provider() {
    return PROVIDER;
  }
}
