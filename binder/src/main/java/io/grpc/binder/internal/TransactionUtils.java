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

package io.grpc.binder.internal;

import android.os.Parcel;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import javax.annotation.Nullable;

/** Constants and helpers for managing inbound / outbound transactions. */
final class TransactionUtils {
  /** Set when the transaction contains rpc prefix data. */
  static final int FLAG_PREFIX = 0x1;
  /** Set when the transaction contains some message data. */
  static final int FLAG_MESSAGE_DATA = 0x2;
  /** Set when the transaction contains rpc suffix data. */
  static final int FLAG_SUFFIX = 0x4;
  /** Set when the transaction is an out-of-band close event. */
  static final int FLAG_OUT_OF_BAND_CLOSE = 0x8;

  /**
   * When a transaction contains client prefix data, this will be set if the rpc being made is
   * expected to return a single message. (I.e the method type is either {@link MethodType#UNARY},
   * or {@link MethodType#CLIENT_STREAMING}).
   */
  static final int FLAG_EXPECT_SINGLE_MESSAGE = 0x10;

  /** Set when the included status data includes a description string. */
  static final int FLAG_STATUS_DESCRIPTION = 0x20;

  /** When a transaction contains message data, this will be set if the message is a parcelable. */
  static final int FLAG_MESSAGE_DATA_IS_PARCELABLE = 0x40;

  /**
   * When a transaction contains message data, this will be set if the message is only partial, and
   * further transactions are required.
   */
  static final int FLAG_MESSAGE_DATA_IS_PARTIAL = 0x80;

  static final int STATUS_CODE_SHIFT = 16;
  static final int STATUS_CODE_MASK = 0xff0000;

  /** The maximum string length for a status description. */
  private static final int MAX_STATUS_DESCRIPTION_LENGTH = 1000;

  private TransactionUtils() {}

  static boolean hasFlag(int flags, int flag) {
    return (flags & flag) != 0;
  }

  @Nullable
  private static String getTruncatedDescription(Status status) {
    String desc = status.getDescription();
    if (desc != null && desc.length() > MAX_STATUS_DESCRIPTION_LENGTH) {
      desc = desc.substring(0, MAX_STATUS_DESCRIPTION_LENGTH);
    }
    return desc;
  }

  static Status readStatus(int flags, Parcel parcel) {
    Status status = Status.fromCodeValue((flags & STATUS_CODE_MASK) >> STATUS_CODE_SHIFT);
    if ((flags & FLAG_STATUS_DESCRIPTION) != 0) {
      status = status.withDescription(parcel.readString());
    }
    return status;
  }

  static int writeStatus(Parcel parcel, Status status) {
    int flags = status.getCode().value() << STATUS_CODE_SHIFT;
    String desc = getTruncatedDescription(status);
    if (desc != null) {
      flags |= FLAG_STATUS_DESCRIPTION;
      parcel.writeString(desc);
    }
    return flags;
  }

  static void fillInFlags(Parcel parcel, int flags) {
    int pos = parcel.dataPosition();
    parcel.setDataPosition(0);
    parcel.writeInt(flags);
    parcel.setDataPosition(pos);
  }
}
