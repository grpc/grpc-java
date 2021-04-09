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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.os.Parcel;
import android.os.Parcelable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
 * An inputstream to serialize a single Android Parcelable object for gRPC calls, with support for
 * serializing to a native Android Parcel, and for when a Parcelable is sent in-process.
 *
 * <p><b>Important:</b> It's not actually possible to marshall a parcelable to raw bytes without
 * losing data, since a parcelable may contain file descriptors. While this class <i>does</i>
 * support marshalling into bytes, this is only supported for the purposes of debugging/logging, and
 * we intentionally don't support unmarshalling back to a parcelable.
 *
 * <p>This class really just wraps a Parcelable instance and masquerardes as an inputstream. See
 * {@code ProtoLiteUtils} for a similar example of this pattern.
 *
 * <p>An instance of this class maybe be created from two sources.
 *
 * <ul>
 *   <li>To wrap a Parcelable instance we plan to send.
 *   <li>To wrap a Parcelable instance we've just received (and read from a Parcel).
 * </ul>
 *
 * <p>In the first case, we expect to serialize to a {@link Parcel}, with a call to {@link
 * #writeToParcel}.
 *
 * <p>In the second case, we only expect the Parcelable to be fetched (and not re-serialized).
 *
 * <p>For in-process gRPC calls, the same InputStream used to send the Parcelable (the first case),
 * will also be used to parse the parcelable from the stream, in which case we shortcut serializing
 * internally (possibly skipping it entirely if the instance is immutable).
 */
final class ParcelableInputStream<P extends Parcelable> extends InputStream {
  @Nullable private final Parcelable.Creator<P> creator;
  private final boolean safeToReturnValue;
  private final P value;

  @Nullable InputStream delegateStream;

  @Nullable P sharableValue;

  ParcelableInputStream(
      @Nullable Parcelable.Creator<P> creator, P value, boolean safeToReturnValue) {
    this.creator = creator;
    this.value = value;
    this.safeToReturnValue = safeToReturnValue;
    // If we're not given a creator, the value must be safe to return unchanged.
    checkArgument(creator != null || safeToReturnValue);
  }

  /**
   * Create a stream from a {@link Parcel} object. Note that this immediately reads the Parcelable
   * object, allowing the Parcel to be recycled after calling this method.
   */
  @SuppressWarnings("unchecked")
  static <P extends Parcelable> ParcelableInputStream<P> readFromParcel(
      Parcel parcel, ClassLoader classLoader) {
    P value = (P) parcel.readParcelable(classLoader);
    return new ParcelableInputStream<>(null, value, true);
  }

  /** Create a stream for a Parcelable object. */
  static <P extends Parcelable> ParcelableInputStream<P> forInstance(
      P value, Parcelable.Creator<P> creator) {
    return new ParcelableInputStream<>(creator, value, false);
  }

  /** Create a stream for a Parcelable object, treating the object as immutable. */
  static <P extends Parcelable> ParcelableInputStream<P> forImmutableInstance(
      P value, Parcelable.Creator<P> creator) {
    return new ParcelableInputStream<>(creator, value, true);
  }

  private InputStream getDelegateStream() {
    if (delegateStream == null) {
      Parcel parcel = Parcel.obtain();
      parcel.writeParcelable(value, 0);
      byte[] res = parcel.marshall();
      parcel.recycle();
      delegateStream = new ByteArrayInputStream(res);
    }
    return delegateStream;
  }

  @Override
  public int read() throws IOException {
    return getDelegateStream().read();
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    return getDelegateStream().read(b, off, len);
  }

  @Override
  public long skip(long n) throws IOException {
    if (n <= 0) {
      return 0;
    }
    return getDelegateStream().skip(n);
  }

  @Override
  public int available() throws IOException {
    return getDelegateStream().available();
  }

  @Override
  public void close() throws IOException {
    if (delegateStream != null) {
      delegateStream.close();
    }
  }

  @Override
  public void mark(int readLimit) {
    // If there's no delegate stream yet, the current position is 0. That's the same
    // as the default mark position, so there's nothing to do.
    if (delegateStream != null) {
      delegateStream.mark(readLimit);
    }
  }

  @Override
  public void reset() throws IOException {
    if (delegateStream != null) {
      delegateStream.reset();
    }
  }

  @Override
  public boolean markSupported() {
    // We know our delegate (ByteArrayInputStream) supports mark/reset.
    return true;
  }

  /**
   * Write the {@link Parcelable} this stream wraps to the given {@link Parcel}.
   *
   * <p>This will retain any android-specific data (e.g. file descriptors) which can't simply be
   * serialized to bytes.
   *
   * @return The number of bytes written to the parcel.
   */
  int writeToParcel(Parcel parcel) {
    int startPos = parcel.dataPosition();
    parcel.writeParcelable(value, value.describeContents());
    return parcel.dataPosition() - startPos;
  }

  /**
   * Get the parcelable as if it had been serialized/de-serialized.
   *
   * <p>If the parcelable is immutable, or it was already de-serialized from a Parcel (I.e. this
   * instance was created with #readFromParcel), the value will be returned directly.
   */
  P getParcelable() {
    if (safeToReturnValue) {
      // We can just return the value directly.
      return value;
    } else {
      // We need to serialize/de-serialize to a parcel internally.
      if (sharableValue == null) {
        sharableValue = marshallUnmarshall(value, checkNotNull(creator));
      }
      return sharableValue;
    }
  }

  private static <P extends Parcelable> P marshallUnmarshall(
      P value, Parcelable.Creator<P> creator) {
    // Serialize/de-serialize the object directly instead of using Parcel.writeParcelable,
    // since there's no need to write out the class name.
    Parcel parcel = Parcel.obtain();
    value.writeToParcel(parcel, 0);
    parcel.setDataPosition(0);
    P result = creator.createFromParcel(parcel);
    parcel.recycle();
    return result;
  }

  @Override
  public String toString() {
    return "ParcelableInputStream[V: " + value + "]";
  }
}
