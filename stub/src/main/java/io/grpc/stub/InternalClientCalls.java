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
