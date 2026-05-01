/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.xds.internal;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.Internal;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converter for Protobuf {@link Struct} to JSON-like {@link Map}.
 */
@Internal
public final class ProtobufJsonConverter {
  private ProtobufJsonConverter() {}

  public static Map<String, Object> convertToJson(Struct struct) {
    Map<String, Object> result = new HashMap<>();
    for (Map.Entry<String, Value> entry : struct.getFieldsMap().entrySet()) {
      result.put(entry.getKey(), convertValue(entry.getValue()));
    }
    return result;
  }

  private static Object convertValue(Value value) {
    switch (value.getKindCase()) {
      case STRUCT_VALUE:
        return convertToJson(value.getStructValue());
      case LIST_VALUE:
        return value.getListValue().getValuesList().stream()
            .map(ProtobufJsonConverter::convertValue)
            .collect(Collectors.toList());
      case NUMBER_VALUE:
        return value.getNumberValue();
      case STRING_VALUE:
        return value.getStringValue();
      case BOOL_VALUE:
        return value.getBoolValue();
      case NULL_VALUE:
        return null;
      default:
        throw new IllegalArgumentException("Unknown Value type: " + value.getKindCase());
    }
  }
}
