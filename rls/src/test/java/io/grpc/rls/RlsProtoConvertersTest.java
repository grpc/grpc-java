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

package io.grpc.rls;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.base.Converter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.internal.JsonParser;
import io.grpc.lookup.v1.RouteLookupRequest;
import io.grpc.lookup.v1.RouteLookupResponse;
import io.grpc.rls.RlsProtoConverters.RouteLookupConfigConverter;
import io.grpc.rls.RlsProtoConverters.RouteLookupRequestConverter;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
import io.grpc.rls.RlsProtoData.ExtraKeys;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder.Name;
import io.grpc.rls.RlsProtoData.NameMatcher;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RlsProtoConvertersTest {

  @Test
  public void convert_toRequestProto() {
    Converter<RouteLookupRequest, RlsProtoData.RouteLookupRequest> converter =
        new RouteLookupRequestConverter();
    RouteLookupRequest proto = RouteLookupRequest.newBuilder()
        .putKeyMap("key1", "val1")
        .build();

    RlsProtoData.RouteLookupRequest object = converter.convert(proto);

    assertThat(object.keyMap()).containsExactly("key1", "val1");
  }

  @Test
  public void convert_toRequestObject() {
    Converter<RlsProtoData.RouteLookupRequest, RouteLookupRequest> converter =
        new RouteLookupRequestConverter().reverse();
    RlsProtoData.RouteLookupRequest requestObject =
        RlsProtoData.RouteLookupRequest.create(ImmutableMap.of("key1", "val1"));

    RouteLookupRequest proto = converter.convert(requestObject);

    assertThat(proto.getTargetType()).isEqualTo("grpc");
    assertThat(proto.getKeyMapMap()).containsExactly("key1", "val1");
  }

  @Test
  public void convert_toResponseProto() {
    Converter<RouteLookupResponse, RlsProtoData.RouteLookupResponse> converter =
        new RouteLookupResponseConverter();
    RouteLookupResponse proto = RouteLookupResponse.newBuilder()
        .addTargets("target")
        .setHeaderData("some header data")
        .build();

    RlsProtoData.RouteLookupResponse object = converter.convert(proto);

    assertThat(object.targets()).containsExactly("target");
    assertThat(object.getHeaderData()).isEqualTo("some header data");
  }

  @Test
  public void convert_toResponseObject() {
    Converter<RlsProtoData.RouteLookupResponse, RouteLookupResponse> converter =
        new RouteLookupResponseConverter().reverse();

    RlsProtoData.RouteLookupResponse object =
        RlsProtoData.RouteLookupResponse.create(ImmutableList.of("target"), "some header data");

    RouteLookupResponse proto = converter.convert(object);

    assertThat(proto.getTargetsList()).containsExactly("target");
    assertThat(proto.getHeaderData()).isEqualTo("some header data");
  }

  @Test
  public void convert_jsonRlsConfig() throws IOException {
    String jsonStr = "{\n"
        + "  \"grpcKeybuilders\": [\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"service1\",\n"
        + "          \"method\": \"create\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": [\n"
        + "        {\n"
        + "          \"key\": \"user\","
        + "          \"names\": [\"User\", \"Parent\"],\n"
        + "          \"optional\": true\n"
        + "        },\n"
        + "        {\n"
        + "          \"key\": \"id\","
        + "          \"names\": [\"X-Google-Id\"],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      ]\n"
        + "    },\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"service1\",\n"
        + "          \"method\": \"*\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": [\n"
        + "        {\n"
        + "          \"key\": \"user\","
        + "          \"names\": [\"User\", \"Parent\"],\n"
        + "          \"optional\": true\n"
        + "        },\n"
        + "        {\n"
        + "          \"key\": \"password\","
        + "          \"names\": [\"Password\"],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      ]\n"
        + "    },\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"service3\",\n"
        + "          \"method\": \"*\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": ["
        + "        {\n"
        + "          \"key\": \"user\","
        + "          \"names\": [\"User\", \"Parent\"],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      ],\n"
        + "      \"extraKeys\": {\n"
        + "        \"host\": \"host-key\",\n"
        + "        \"service\": \"service-key\",\n"
        + "        \"method\": \"method-key\"\n"
        + "      }, \n"
        + "      \"constantKeys\": {\n"
        + "        \"constKey1\": \"value1\"\n"
        + "      }\n"
        + "    }\n"
        + "  ],\n"
        + "  \"lookupService\": \"service1\",\n"
        + "  \"lookupServiceTimeout\": \"2s\",\n"
        + "  \"maxAge\": \"300s\",\n"
        + "  \"staleAge\": \"240s\",\n"
        + "  \"validTargets\": [\"a valid target\"],"
        + "  \"cacheSizeBytes\": \"1000\",\n"
        + "  \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\"\n"
        + "}";

    RouteLookupConfig expectedConfig =
        RouteLookupConfig.builder()
            .grpcKeybuilders(ImmutableList.of(
                GrpcKeyBuilder.create(
                    ImmutableList.of(Name.create("service1", "create")),
                    ImmutableList.of(
                        NameMatcher.create("user", ImmutableList.of("User", "Parent")),
                        NameMatcher.create("id", ImmutableList.of("X-Google-Id"))),
                    ExtraKeys.DEFAULT,
                    ImmutableMap.<String, String>of()),
                GrpcKeyBuilder.create(
                    ImmutableList.of(Name.create("service1", "*")),
                    ImmutableList.of(
                        NameMatcher.create("user", ImmutableList.of("User", "Parent")),
                        NameMatcher.create("password", ImmutableList.of("Password"))),
                    ExtraKeys.DEFAULT,
                    ImmutableMap.<String, String>of()),
                GrpcKeyBuilder.create(
                    ImmutableList.of(Name.create("service3", "*")),
                    ImmutableList.of(
                        NameMatcher.create("user", ImmutableList.of("User", "Parent"))),
                    ExtraKeys.create("host-key", "service-key", "method-key"),
                    ImmutableMap.of("constKey1", "value1"))))
            .lookupService("service1")
            .lookupServiceTimeoutInNanos(TimeUnit.SECONDS.toNanos(2))
            .maxAgeInNanos(TimeUnit.SECONDS.toNanos(300))
            .staleAgeInNanos(TimeUnit.SECONDS.toNanos(240))
            .cacheSizeBytes(1000)
            .defaultTarget("us_east_1.cloudbigtable.googleapis.com")
            .build();

    RouteLookupConfigConverter converter = new RouteLookupConfigConverter();
    @SuppressWarnings("unchecked")
    Map<String, ?> parsedJson = (Map<String, ?>) JsonParser.parse(jsonStr);
    RouteLookupConfig converted = converter.convert(parsedJson);
    assertThat(converted).isEqualTo(expectedConfig);
  }

  @Test
  public void convert_jsonRlsConfig_emptyKeyBuilders()  throws IOException {
    String jsonStr = "{\n"
        + "  \"grpcKeybuilders\": [],\n"
        + "  \"lookupService\": \"service1\",\n"
        + "  \"lookupServiceTimeout\": \"2s\",\n"
        + "  \"maxAge\": \"300s\",\n"
        + "  \"staleAge\": \"240s\",\n"
        + "  \"validTargets\": [\"a valid target\"],"
        + "  \"cacheSizeBytes\": \"1000\",\n"
        + "  \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\"\n"
        + "}";

    RouteLookupConfigConverter converter = new RouteLookupConfigConverter();
    @SuppressWarnings("unchecked")
    Map<String, ?> parsedJson = (Map<String, ?>) JsonParser.parse(jsonStr);
    try {
      converter.convert(parsedJson);
      fail("Exception expected");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("must have at least one GrpcKeyBuilder");
    }
  }

  @Test
  public void convert_jsonRlsConfig_namesNotUnique() throws IOException {
    String jsonStr = "{\n"
        + "  \"grpcKeybuilders\": [\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"service1\",\n"
        + "          \"method\": \"create\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": [\n"
        + "        {\n"
        + "          \"key\": \"user\","
        + "          \"names\": [\"User\", \"Parent\"],\n"
        + "          \"optional\": true\n"
        + "        },\n"
        + "        {\n"
        + "          \"key\": \"id\","
        + "          \"names\": [\"X-Google-Id\"],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      ]\n"
        + "    },\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"service1\",\n"
        + "          \"method\": \"create\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": [\n"
        + "        {\n"
        + "          \"key\": \"user\","
        + "          \"names\": [\"User\", \"Parent\"],\n"
        + "          \"optional\": true\n"
        + "        },\n"
        + "        {\n"
        + "          \"key\": \"password\","
        + "          \"names\": [\"Password\"],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      ]\n"
        + "    },\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"service3\",\n"
        + "          \"method\": \"*\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": ["
        + "        {\n"
        + "          \"key\": \"user\","
        + "          \"names\": [\"User\", \"Parent\"],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      ],\n"
        + "      \"extraKeys\": {\n"
        + "        \"host\": \"host-key\",\n"
        + "        \"service\": \"service-key\",\n"
        + "        \"method\": \"method-key\"\n"
        + "      }, \n"
        + "      \"constantKeys\": {\n"
        + "        \"constKey1\": \"value1\"\n"
        + "      }\n"
        + "    }\n"
        + "  ],\n"
        + "  \"lookupService\": \"service1\",\n"
        + "  \"lookupServiceTimeout\": \"2s\",\n"
        + "  \"maxAge\": \"300s\",\n"
        + "  \"staleAge\": \"240s\",\n"
        + "  \"validTargets\": [\"a valid target\"],"
        + "  \"cacheSizeBytes\": \"1000\",\n"
        + "  \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\"\n"
        + "}";

    RouteLookupConfigConverter converter = new RouteLookupConfigConverter();
    @SuppressWarnings("unchecked")
    Map<String, ?> parsedJson = (Map<String, ?>) JsonParser.parse(jsonStr);
    try {
      converter.convert(parsedJson);
      fail("Exception expected");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat()
          .contains("duplicate names in grpc_keybuilders: Name{service=service1, method=create}");
    }
  }

  @Test
  public void convert_jsonRlsConfig_defaultValues() throws IOException {
    String jsonStr = "{\n"
        + "  \"grpcKeybuilders\": [\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"service1\"\n"
        + "        }\n"
        + "      ]\n"
        + "    }\n"
        + "  ],\n"
        + "  \"lookupService\": \"service1\",\n"
        + "  \"validTargets\": [\"a valid target\"],"
        + "  \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\"\n"
        + "}";

    RouteLookupConfig expectedConfig =
        RouteLookupConfig.builder()
            .grpcKeybuilders(ImmutableList.of(
                GrpcKeyBuilder.create(
                    ImmutableList.of(Name.create("service1", null)),
                    ImmutableList.<NameMatcher>of(),
                    ExtraKeys.DEFAULT,
                    ImmutableMap.<String, String>of())))
            .lookupService("service1")
            .lookupServiceTimeoutInNanos(TimeUnit.SECONDS.toNanos(10))
            .maxAgeInNanos(TimeUnit.MINUTES.toNanos(5))
            .staleAgeInNanos(TimeUnit.MINUTES.toNanos(5))
            .cacheSizeBytes(5 * 1024 * 1024)
            .defaultTarget("us_east_1.cloudbigtable.googleapis.com")
            .build();

    RouteLookupConfigConverter converter = new RouteLookupConfigConverter();
    @SuppressWarnings("unchecked")
    Map<String, ?> parsedJson = (Map<String, ?>) JsonParser.parse(jsonStr);
    RouteLookupConfig converted = converter.convert(parsedJson);
    assertThat(converted).isEqualTo(expectedConfig);
  }

  @Test
  public void convert_jsonRlsConfig_staleAgeCappedByMaxAge() throws IOException {
    String jsonStr = "{\n"
        + "  \"grpcKeybuilders\": [\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"service1\",\n"
        + "          \"method\": \"create\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": [\n"
        + "        {\n"
        + "          \"key\": \"user\","
        + "          \"names\": [\"User\", \"Parent\"],\n"
        + "          \"optional\": true\n"
        + "        },\n"
        + "        {\n"
        + "          \"key\": \"id\","
        + "          \"names\": [\"X-Google-Id\"],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      ]\n"
        + "    }\n"
        + "  ],\n"
        + "  \"lookupService\": \"service1\",\n"
        + "  \"lookupServiceTimeout\": \"2s\",\n"
        + "  \"maxAge\": \"300s\",\n"
        + "  \"staleAge\": \"400s\",\n"
        + "  \"validTargets\": [\"a valid target\"],"
        + "  \"cacheSizeBytes\": \"1000\",\n"
        + "  \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\"\n"
        + "}";

    RouteLookupConfig expectedConfig =
        RouteLookupConfig.builder()
            .grpcKeybuilders(ImmutableList.of(
                GrpcKeyBuilder.create(
                    ImmutableList.of(Name.create("service1", "create")),
                    ImmutableList.of(
                        NameMatcher.create("user", ImmutableList.of("User", "Parent")),
                        NameMatcher.create("id", ImmutableList.of("X-Google-Id"))),
                    ExtraKeys.DEFAULT,
                    ImmutableMap.<String, String>of())))
            .lookupService("service1")
            .lookupServiceTimeoutInNanos(TimeUnit.SECONDS.toNanos(2))
            .maxAgeInNanos(TimeUnit.SECONDS.toNanos(300))
            .staleAgeInNanos(TimeUnit.SECONDS.toNanos(300))
            .cacheSizeBytes(1000)
            .defaultTarget("us_east_1.cloudbigtable.googleapis.com")
            .build();

    RouteLookupConfigConverter converter = new RouteLookupConfigConverter();
    @SuppressWarnings("unchecked")
    Map<String, ?> parsedJson = (Map<String, ?>) JsonParser.parse(jsonStr);
    RouteLookupConfig converted = converter.convert(parsedJson);
    assertThat(converted).isEqualTo(expectedConfig);
  }

  @Test
  public void convert_jsonRlsConfig_staleAgeGivenWithoutMaxAge() throws IOException {
    String jsonStr = "{\n"
        + "  \"grpcKeybuilders\": [\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"service1\",\n"
        + "          \"method\": \"create\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": [\n"
        + "        {\n"
        + "          \"key\": \"user\","
        + "          \"names\": [\"User\", \"Parent\"],\n"
        + "          \"optional\": true\n"
        + "        },\n"
        + "        {\n"
        + "          \"key\": \"id\","
        + "          \"names\": [\"X-Google-Id\"],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      ]\n"
        + "    }\n"
        + "  ],\n"
        + "  \"lookupService\": \"service1\",\n"
        + "  \"lookupServiceTimeout\": \"2s\",\n"
        + "  \"staleAge\": \"240s\",\n"
        + "  \"validTargets\": [\"a valid target\"],"
        + "  \"cacheSizeBytes\": \"1000\",\n"
        + "  \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\"\n"
        + "}";

    RouteLookupConfigConverter converter = new RouteLookupConfigConverter();
    @SuppressWarnings("unchecked")
    Map<String, ?> parsedJson = (Map<String, ?>) JsonParser.parse(jsonStr);
    try {
      converter.convert(parsedJson);
      fail("Exception expected");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("to specify staleAge, must have maxAge");
    }
  }

  @Test
  public void convert_jsonRlsConfig_keyBuilderWithoutName() throws IOException {
    String jsonStr = "{\n"
        + "  \"grpcKeybuilders\": [\n"
        + "    {\n"
        + "      \"headers\": [\n"
        + "        {\n"
        + "          \"key\": \"user\","
        + "          \"names\": [\"User\", \"Parent\"],\n"
        + "          \"optional\": true\n"
        + "        },\n"
        + "        {\n"
        + "          \"key\": \"id\","
        + "          \"names\": [\"X-Google-Id\"],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      ]\n"
        + "    }\n"
        + "  ],\n"
        + "  \"lookupService\": \"service1\",\n"
        + "  \"lookupServiceTimeout\": \"2s\",\n"
        + "  \"staleAge\": \"240s\",\n"
        + "  \"validTargets\": [\"a valid target\"],"
        + "  \"cacheSizeBytes\": \"1000\",\n"
        + "  \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\"\n"
        + "}";

    RouteLookupConfigConverter converter = new RouteLookupConfigConverter();
    @SuppressWarnings("unchecked")
    Map<String, ?> parsedJson = (Map<String, ?>) JsonParser.parse(jsonStr);
    try {
      converter.convert(parsedJson);
      fail("Exception expected");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("each keyBuilder must have at least one name");
    }
  }

  @Test
  public void convert_jsonRlsConfig_nameWithoutService() throws IOException {
    String jsonStr = "{\n"
        + "  \"grpcKeybuilders\": [\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"method\": \"create\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": [\n"
        + "        {\n"
        + "          \"key\": \"user\","
        + "          \"names\": [\"User\", \"Parent\"],\n"
        + "          \"optional\": true\n"
        + "        },\n"
        + "        {\n"
        + "          \"key\": \"id\","
        + "          \"names\": [\"X-Google-Id\"],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      ]\n"
        + "    }\n"
        + "  ],\n"
        + "  \"lookupService\": \"service1\",\n"
        + "  \"lookupServiceTimeout\": \"2s\",\n"
        + "  \"staleAge\": \"240s\",\n"
        + "  \"validTargets\": [\"a valid target\"],"
        + "  \"cacheSizeBytes\": \"1000\",\n"
        + "  \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\"\n"
        + "}";

    RouteLookupConfigConverter converter = new RouteLookupConfigConverter();
    @SuppressWarnings("unchecked")
    Map<String, ?> parsedJson = (Map<String, ?>) JsonParser.parse(jsonStr);
    try {
      converter.convert(parsedJson);
      fail("Exception expected");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("service must not be empty or null");
    }
  }

  @Test
  public void convert_jsonRlsConfig_keysNotUnique() throws IOException {
    String jsonStr = "{\n"
        + "  \"grpcKeybuilders\": [\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"service1\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": [\n"
        + "        {\n"
        + "          \"key\": \"service\","  // duplicate to extra_keys
        + "          \"names\": [\"User\", \"Parent\"],\n"
        + "          \"optional\": true\n"
        + "        },\n"
        + "        {\n"
        + "          \"key\": \"id\","
        + "          \"names\": [\"X-Google-Id\"],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      ]\n"
        + "    }\n"
        + "  ],\n"
        + "  \"lookupService\": \"service1\",\n"
        + "  \"lookupServiceTimeout\": \"2s\",\n"
        + "  \"staleAge\": \"240s\",\n"
        + "  \"validTargets\": [\"a valid target\"],"
        + "  \"cacheSizeBytes\": \"1000\",\n"
        + "  \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\"\n"
        + "}";

    RouteLookupConfigConverter converter = new RouteLookupConfigConverter();
    @SuppressWarnings("unchecked")
    Map<String, ?> parsedJson = (Map<String, ?>) JsonParser.parse(jsonStr);
    try {
      converter.convert(parsedJson);
      fail("Exception expected");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("keys in KeyBuilder must be unique");
    }
  }
}
