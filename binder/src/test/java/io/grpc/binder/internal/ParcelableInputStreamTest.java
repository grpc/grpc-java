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

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;
import android.os.Parcelable;
import com.google.common.io.ByteStreams;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class ParcelableInputStreamTest {

  private final TestParcelable testParcelable = new TestParcelable("testing");
  private final TestParcelable testParcelableWithFds =
      new TestParcelable("testing_with_fds", Parcelable.CONTENTS_FILE_DESCRIPTOR);

  @Test
  public void testGetParcelable() throws Exception {
    ParcelableInputStream<TestParcelable> stream =
        ParcelableInputStream.forInstance(testParcelable, TestParcelable.CREATOR);

    // We should serialize/deserialize the parcelable.
    TestParcelable parceable = stream.getParcelable();
    assertThat(parceable).isEqualTo(testParcelable);
    assertThat(parceable).isNotSameInstanceAs(testParcelable);

    // But just once.
    assertThat(stream.getParcelable()).isSameInstanceAs(parceable);
  }

  @Test
  public void testGetParcelableWithFds() throws Exception {
    ParcelableInputStream<TestParcelable> stream =
        ParcelableInputStream.forInstance(testParcelableWithFds, TestParcelable.CREATOR);

    // We should serialize/deserialize the parcelable.
    TestParcelable parceable = stream.getParcelable();
    assertThat(parceable).isEqualTo(testParcelableWithFds);
    assertThat(parceable).isNotSameInstanceAs(testParcelableWithFds);

    // But just once.
    assertThat(stream.getParcelable()).isSameInstanceAs(parceable);
  }

  @Test
  public void testGetParcelableImmutable() throws Exception {
    ParcelableInputStream<TestParcelable> stream =
        ParcelableInputStream.forImmutableInstance(testParcelable, TestParcelable.CREATOR);

    // We should return the parcelable directly.
    TestParcelable parceable = stream.getParcelable();
    assertThat(parceable).isSameInstanceAs(testParcelable);
  }

  @Test
  public void testGetParcelableImmutableWithFds() throws Exception {
    ParcelableInputStream<TestParcelable> stream =
        ParcelableInputStream.forImmutableInstance(testParcelableWithFds, TestParcelable.CREATOR);

    // We should return the parcelable directly.
    TestParcelable parceable = stream.getParcelable();
    assertThat(parceable).isSameInstanceAs(testParcelableWithFds);
  }

  @Test
  public void testWriteToParcel() throws Exception {
    ParcelableInputStream<TestParcelable> stream =
        ParcelableInputStream.forImmutableInstance(testParcelable, TestParcelable.CREATOR);
    Parcel parcel = Parcel.obtain();
    stream.writeToParcel(parcel);

    parcel.setDataPosition(0);
    assertThat((TestParcelable) parcel.readParcelable(getClass().getClassLoader()))
        .isEqualTo(testParcelable);
  }

  @Test
  public void testCreateFromParcel() throws Exception {
    Parcel parcel = Parcel.obtain();
    parcel.writeParcelable(testParcelable, 0);
    parcel.setDataPosition(0);

    ParcelableInputStream<TestParcelable> stream =
        ParcelableInputStream.readFromParcel(parcel, getClass().getClassLoader());
    assertThat(stream.getParcelable()).isEqualTo(testParcelable);
  }

  @Test
  public void testAsRegularInputStream() throws Exception {
    ParcelableInputStream<TestParcelable> stream =
        ParcelableInputStream.forInstance(testParcelable, TestParcelable.CREATOR);
    byte[] data = ByteStreams.toByteArray(stream);

    Parcel parcel = Parcel.obtain();
    parcel.unmarshall(data, 0, data.length);
    parcel.setDataPosition(0);

    assertThat((TestParcelable) parcel.readParcelable(getClass().getClassLoader()))
        .isEqualTo(testParcelable);
  }

  @Test
  public void testAsRegularInputStreamFds() throws Exception {
    ParcelableInputStream<TestParcelable> stream =
        ParcelableInputStream.forInstance(testParcelableWithFds, TestParcelable.CREATOR);
    byte[] data = ByteStreams.toByteArray(stream);
    assertThat(data.length).isNotEqualTo(0);
  }
}
