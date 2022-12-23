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

package io.grpc.binder;

import android.os.Parcelable;
import io.grpc.Metadata;
import io.grpc.binder.internal.MetadataHelper;

/**
 * Utility methods for using Android Parcelable objects with gRPC.
 *
 * <p>This class models the same pattern as the {@code ProtoLiteUtils} class.
 */
public final class ParcelableUtils {

  private ParcelableUtils() {}

  /**
   * Create a {@link Metadata.Key} for passing a Parcelable object in the metadata of an RPC,
   * treating instances as mutable.
   *
   * <p><b>Note:<b/>Parcelables can only be sent across in-process and binder channels.
   */
  public static <P extends Parcelable> Metadata.Key<P> metadataKey(
      String name, Parcelable.Creator<P> creator) {
    return Metadata.Key.of(
        name, new MetadataHelper.ParcelableMetadataMarshaller<P>(creator, false));
  }

  /**
   * Create a {@link Metadata.Key} for passing a Parcelable object in the metadata of an RPC,
   * treating instances as immutable. Immutability may be used for optimization purposes (e.g. Not
   * copying for in-process calls).
   *
   * <p><b>Note:<b/>Parcelables can only be sent across in-process and binder channels.
   */
  public static <P extends Parcelable> Metadata.Key<P> metadataKeyForImmutableType(
      String name, Parcelable.Creator<P> creator) {
    return Metadata.Key.of(
        name, new MetadataHelper.ParcelableMetadataMarshaller<P>(creator, true));
  }
}

