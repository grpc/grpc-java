/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.autosharding;

import io.grpc.ConnectivityState;
import io.grpc.InternalMetadata;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

final class AutoShardingPicker extends SubchannelPicker {
  private static final byte[] EMPTY_BYTES = new byte[0];

  private static final InternalMetadata.TrustedAsciiMarshaller<byte[]> RAW_ASCII_MARSHALLER =
      new InternalMetadata.TrustedAsciiMarshaller<byte[]>() {
        @Override
        public byte[] toAsciiString(byte[] value) {
          return value;
        }

        @Override
        public byte[] parseAsciiString(byte[] serialized) {
          return serialized;
        }
      };

  private final SliceMap sliceMap;
  private final List<PickerEndpoint> endpoints;
  private final boolean[] sliceInFallback;
  private final boolean fallbackEnabled;
  private final Metadata.Key<byte[]> sliceKeyHeader;

  AutoShardingPicker(
      SliceMap sliceMap,
      List<PickerEndpoint> endpoints,
      boolean fallbackEnabled,
      String sliceKeyHeaderName) {
    this.sliceMap = sliceMap;
    this.endpoints = Collections.unmodifiableList(new ArrayList<>(endpoints));
    this.fallbackEnabled = fallbackEnabled;

    if (sliceKeyHeaderName == null || sliceKeyHeaderName.isEmpty()) {
      this.sliceKeyHeader = null;
    } else if (sliceKeyHeaderName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
      this.sliceKeyHeader = Metadata.Key.of(sliceKeyHeaderName, Metadata.BINARY_BYTE_MARSHALLER);
    } else {
      this.sliceKeyHeader = InternalMetadata.keyOf(sliceKeyHeaderName, RAW_ASCII_MARSHALLER);
    }

    this.sliceInFallback = new boolean[sliceMap.getSlices().size()];
    for (int i = 0; i < sliceInFallback.length; i++) {
      this.sliceInFallback[i] = isPoolInFallback(sliceMap.getSlices().get(i).endpoints);
    }
  }

  private boolean isPoolInFallback(List<Integer> indices) {
    if (indices.isEmpty()) {
      return true;
    }
    for (int idx : indices) {
      if (endpoints.get(idx).state != ConnectivityState.TRANSIENT_FAILURE) {
        return false;
      }
    }
    return true;
  }

  @Override
  public PickResult pickSubchannel(PickSubchannelArgs args) {
    byte[] key = extractKeyBytes(args.getHeaders());
    Integer sliceIdx = sliceMap.lookup(key);

    if (sliceIdx == null) {
      if (fallbackEnabled) {
        return pickFromEndpointIndices(sliceMap.getFallbackPool(), args);
      } else {
        return PickResult.withError(
            Status.UNAVAILABLE.withDescription(
                "No sharding assignment available and fallback disabled"));
      }
    }

    if (sliceInFallback[sliceIdx] && fallbackEnabled) {
      return pickFromEndpointIndices(sliceMap.getFallbackPool(), args);
    }

    SliceMap.SliceEntry sliceEntry = sliceMap.getSlices().get(sliceIdx);
    return pickFromEndpointIndices(sliceEntry.endpoints, args);
  }

  private PickResult pickFromEndpointIndices(
      List<Integer> indices, PickSubchannelArgs args) {
    if (indices.isEmpty()) {
      return PickResult.withError(
          Status.UNAVAILABLE.withDescription("No valid endpoints in slice and fallback disabled"));
    }

    int size = indices.size();
    int firstIndex = ThreadLocalRandom.current().nextInt(size);
    boolean requestedConnection = false;
    boolean foundConnecting = false;

    for (int i = 0; i < size; i++) {
      int epIdx = indices.get((firstIndex + i) % size);
      PickerEndpoint endpoint = endpoints.get(epIdx);

      if (endpoint.state == ConnectivityState.READY) {
        return endpoint.picker.pickSubchannel(args);
      }

      if (endpoint.state == ConnectivityState.CONNECTING) {
        foundConnecting = true;
      } else if (!requestedConnection && endpoint.state == ConnectivityState.IDLE) {
        if (endpoint.requestConnection != null) {
          endpoint.requestConnection.run();
        }
        requestedConnection = true;
      }
    }

    if (requestedConnection || foundConnecting) {
      return PickResult.withNoResult("connecting", "Waiting for endpoint connection");
    }

    int firstEpIdx = indices.get(firstIndex);
    return endpoints.get(firstEpIdx).picker.pickSubchannel(args);
  }

  private byte[] extractKeyBytes(Metadata headers) {
    if (sliceKeyHeader != null) {
      byte[] val = headers.get(sliceKeyHeader);
      return val != null ? val : EMPTY_BYTES;
    }
    return EMPTY_BYTES;
  }
}
