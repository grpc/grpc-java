/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.stub;

import io.grpc.CallOptions;
import io.grpc.Internal;

/**
 * Internal {@link ClientCalls} accessor.  This is intended for usage internal to the gRPC
 * team.  If you *really* think you need to use this, contact the gRPC team first.
 */
@Internal
public final class InternalClientCalls {

  /** Internal accessor for {@link ClientCalls#CALL_TYPE_OPTION}. */
  public static CallOptions.Key<ClientCalls.CallType> getCallTypeOption() {
    return ClientCalls.CALL_TYPE_OPTION;
  }

  /** Companion enum for internal enum {@link ClientCalls.CallType}. */
  public enum CallType {
    BLOCKING(ClientCalls.CallType.BLOCKING),
    ASYNC(ClientCalls.CallType.ASYNC),
    FUTURE(ClientCalls.CallType.FUTURE);

    private final ClientCalls.CallType internalType;

    CallType(ClientCalls.CallType internalType) {
      this.internalType = internalType;
    }

    /** Returns companion enum value of passed internal enum equivalent. */
    public static CallType of(ClientCalls.CallType internal) {
      for (CallType value : CallType.values()) {
        if (value.internalType == internal) {
          return value;
        }
      }
      throw new AssertionError("Unknown CallType: " + internal.name());
    }
  }
}
