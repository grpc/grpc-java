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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ProtobufJsonConverterTest {

  @Test
  public void testEmptyStruct() {
    Struct emptyStruct = Struct.newBuilder().build();
    Map<String, Object> result = ProtobufJsonConverter.convertToJson(emptyStruct);
    assertTrue(result.isEmpty());
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

    assertEquals("stringValue", result.get("stringKey"));
    assertEquals(123.45, result.get("numberKey"));
    assertEquals(true, result.get("boolKey"));
    assertNull(result.get("nullKey"));

    Object nestedStruct = result.get("structKey");
    assertTrue(nestedStruct instanceof Map);
    assertEquals("nestedValue", ((Map<?, ?>) nestedStruct).get("nestedKey"));

    Object objList = result.get("listKey");
    assertTrue(objList instanceof List);
    List<?> list = (List<?>) objList;
    assertEquals(3, list.size());
    assertEquals(1.0, list.get(0));
    assertEquals("two", list.get(1));
    assertEquals(false, list.get(2));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testUnknownValueType() {
    Value unknownValue = Value.newBuilder().build(); // Default instance with no kind case set.
    ProtobufJsonConverter.convertToJson(
        Struct.newBuilder().putFields("unknownKey", unknownValue).build());
  }
}
