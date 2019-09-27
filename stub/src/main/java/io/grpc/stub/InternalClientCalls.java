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

  /** Internal accessor for {@link ClientCalls#STUB_TYPE_OPTION}. */
  public static CallOptions.Key<ClientCalls.StubType> getStubTypeOption() {
    return ClientCalls.STUB_TYPE_OPTION;
  }

  /** Returns {@link StubType} from call options. */
  public static StubType getStubType(CallOptions callOptions) {
    return StubType.of(callOptions.getOption(ClientCalls.STUB_TYPE_OPTION));
  }

  /** Companion enum for internal enum {@link ClientCalls.StubType}. */
  public enum StubType {
    BLOCKING(ClientCalls.StubType.BLOCKING),
    ASYNC(ClientCalls.StubType.ASYNC),
    FUTURE(ClientCalls.StubType.FUTURE);

    private final ClientCalls.StubType internalType;

    StubType(ClientCalls.StubType internalType) {
      this.internalType = internalType;
    }

    /** Returns companion enum value of passed internal enum equivalent. */
    public static StubType of(ClientCalls.StubType internal) {
      for (StubType value : StubType.values()) {
        if (value.internalType == internal) {
          return value;
        }
      }
      throw new AssertionError("Unknown StubType: " + internal.name());
    }
  }
}
