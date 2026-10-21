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
import android.os.Parcelable;

/** A parcelable for testing. */
public class TestParcelable implements Parcelable {
  private final String msg;
  private final int contents;

  public TestParcelable(String msg) {
    this(msg, 0);
  }

  public TestParcelable(String msg, int contents) {
    this.msg = msg;
    this.contents = contents;
  }

  @Override
  public int describeContents() {
    return contents;
  }

  @Override
  public void writeToParcel(Parcel parcel, int flags) {
    parcel.writeString(msg);
  }

  @Override
  public int hashCode() {
    return msg.hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof TestParcelable) {
      return msg.equals(((TestParcelable) other).msg);
    }
    return false;
  }

  public static final Parcelable.Creator<TestParcelable> CREATOR =
      new Parcelable.Creator<TestParcelable>() {
        @Override
        public TestParcelable createFromParcel(Parcel parcel) {
          return new TestParcelable(parcel.readString(), 0);
        }

        @Override
        public TestParcelable[] newArray(int size) {
          return new TestParcelable[size];
        }
      };
}
