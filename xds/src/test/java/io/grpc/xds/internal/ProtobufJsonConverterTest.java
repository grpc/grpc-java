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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ProtobufJsonConverterTest {

  @Test
  public void testEmptyStruct() {
    Struct emptyStruct = Struct.newBuilder().build();
    Map<String, Object> result = ProtobufJsonConverter.convertToJson(emptyStruct);
    assertThat(result).isEmpty();
  }

  @Test
  public void testStructWithValues() {
    Struct struct = Struct.newBuilder()
        .putFields("stringKey", Value.newBuilder().setStringValue("stringValue").build())
        .putFields("numberKey", Value.newBuilder().setNumberValue(123.45).build())
        .putFields("boolKey", Value.newBuilder().setBoolValue(true).build())
        .putFields("nullKey", Value.newBuilder().setNullValueValue(0).build())
        .putFields("structKey", Value.newBuilder()
            .setStructValue(Struct.newBuilder()
                .putFields("nestedKey", Value.newBuilder().setStringValue("nestedValue").build())
                .build())
            .build())
        .putFields("listKey", Value.newBuilder()
            .setListValue(ListValue.newBuilder()
                .addValues(Value.newBuilder().setNumberValue(1).build())
                .addValues(Value.newBuilder().setStringValue("two").build())
                .addValues(Value.newBuilder().setBoolValue(false).build())
                .build())
            .build())
        .build();

    Map<String, Object> result = ProtobufJsonConverter.convertToJson(struct);

    Map<String, Object> goldenResult = new HashMap<>();
    goldenResult.put("stringKey", "stringValue");
    goldenResult.put("numberKey", 123.45);
    goldenResult.put("boolKey", true);
    goldenResult.put("nullKey", null);
    goldenResult.put("structKey", ImmutableMap.of("nestedKey", "nestedValue"));
    goldenResult.put("listKey", Arrays.asList(1.0, "two", false));

    assertEquals(goldenResult, result);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testUnknownValueType() {
    Value unknownValue = Value.newBuilder().build(); // Default instance with no kind case set.
    ProtobufJsonConverter.convertToJson(
        Struct.newBuilder().putFields("unknownKey", unknownValue).build());
  }
}
