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

import com.google.common.base.Converter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.internal.JsonParser;
import io.grpc.lookup.v1.RouteLookupRequest;
import io.grpc.lookup.v1.RouteLookupResponse;
import io.grpc.rls.RlsProtoConverters.RouteLookupConfigConverter;
import io.grpc.rls.RlsProtoConverters.RouteLookupRequestConverter;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
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
        .setServer("server")
        .setPath("path")
        .setTargetType("target")
        .putKeyMap("key1", "val1")
        .build();

    RlsProtoData.RouteLookupRequest object = converter.convert(proto);

    assertThat(object.getServer()).isEqualTo("server");
    assertThat(object.getPath()).isEqualTo("path");
    assertThat(object.getTargetType()).isEqualTo("target");
    assertThat(object.getKeyMap()).containsExactly("key1", "val1");
  }

  @Test
  public void convert_toRequestObject() {
    Converter<RlsProtoData.RouteLookupRequest, RouteLookupRequest> converter =
        new RouteLookupRequestConverter().reverse();
    RlsProtoData.RouteLookupRequest requestObject =
        new RlsProtoData.RouteLookupRequest(
            "server", "path", "target", ImmutableMap.of("key1", "val1"));

    RouteLookupRequest proto = converter.convert(requestObject);

    assertThat(proto.getServer()).isEqualTo("server");
    assertThat(proto.getPath()).isEqualTo("path");
    assertThat(proto.getTargetType()).isEqualTo("target");
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

    assertThat(object.getTargets()).containsExactly("target");
    assertThat(object.getHeaderData()).isEqualTo("some header data");
  }

  @Test
  public void convert_toResponseObject() {
    Converter<RlsProtoData.RouteLookupResponse, RouteLookupResponse> converter =
        new RouteLookupResponseConverter().reverse();

    RlsProtoData.RouteLookupResponse object =
        new RlsProtoData.RouteLookupResponse(ImmutableList.of("target"), "some header data");

    RouteLookupResponse proto = converter.convert(object);

    assertThat(proto.getTargetsList()).containsExactly("target");
    assertThat(proto.getHeaderData()).isEqualTo("some header data");
  }

  @Test
  public void convert_jsonRlsConfig() throws IOException {
    String jsonStr = "{\n"
        + "  \"grpcKeyBuilders\": [\n"
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
        + "      ]\n"
        + "    }\n"
        + "  ],\n"
        + "  \"lookupService\": \"service1\",\n"
        + "  \"lookupServiceTimeout\": 2,\n"
        + "  \"maxAge\": 300,\n"
        + "  \"staleAge\": 240,\n"
        + "  \"validTargets\": [\"a valid target\"],"
        + "  \"cacheSizeBytes\": 1000,\n"
        + "  \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\"\n"
        + "}";

    RouteLookupConfig expectedConfig =
        new RouteLookupConfig(
            ImmutableList.of(
                new GrpcKeyBuilder(
                    ImmutableList.of(new Name("service1", "create")),
                    ImmutableList.of(
                        new NameMatcher("user", ImmutableList.of("User", "Parent"), true),
                        new NameMatcher("id", ImmutableList.of("X-Google-Id"), true))),
                new GrpcKeyBuilder(
                    ImmutableList.of(new Name("service1")),
                    ImmutableList.of(
                        new NameMatcher("user", ImmutableList.of("User", "Parent"), true),
                        new NameMatcher("password", ImmutableList.of("Password"), true))),
                new GrpcKeyBuilder(
                    ImmutableList.of(new Name("service3")),
                    ImmutableList.of(
                        new NameMatcher("user", ImmutableList.of("User", "Parent"), true)))),
            /* lookupService= */ "service1",
            /* lookupServiceTimeoutInMillis= */ TimeUnit.SECONDS.toMillis(2),
            /* maxAgeInMillis= */ TimeUnit.SECONDS.toMillis(300),
            /* staleAgeInMillis= */ TimeUnit.SECONDS.toMillis(240),
            /* cacheSizeBytes= */ 1000,
            /* validTargets= */ ImmutableList.of("a valid target"),
            /* defaultTarget= */ "us_east_1.cloudbigtable.googleapis.com");

    RouteLookupConfigConverter converter = new RouteLookupConfigConverter();
    @SuppressWarnings("unchecked")
    Map<String, ?> parsedJson = (Map<String, ?>) JsonParser.parse(jsonStr);
    RouteLookupConfig converted = converter.convert(parsedJson);
    assertThat(converted).isEqualTo(expectedConfig);
  }
}
