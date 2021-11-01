/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.util.Durations;
import io.grpc.lookup.v1.GrpcKeyBuilder;
import io.grpc.lookup.v1.GrpcKeyBuilder.ExtraKeys;
import io.grpc.lookup.v1.GrpcKeyBuilder.Name;
import io.grpc.lookup.v1.NameMatcher;
import io.grpc.lookup.v1.RouteLookupConfig;
import io.grpc.rls.RlsProtoData;
import io.grpc.xds.RouteLookupServiceClusterSpecifierPlugin.RlsPluginConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RouteLookupServiceClusterSpecifierPlugin}. */
@RunWith(JUnit4.class)
public class RouteLookupServiceClusterSpecifierPluginTest {
  @Test
  public void parseConfigWithAllFieldsGiven() {
    RouteLookupConfig routeLookupConfig = RouteLookupConfig.newBuilder()
        .addGrpcKeybuilders(
            GrpcKeyBuilder.newBuilder()
                .addNames(Name.newBuilder().setService("service1").setMethod("method1"))
                .addNames(Name.newBuilder().setService("service2").setMethod("method2"))
                .addHeaders(
                    NameMatcher.newBuilder().setKey("key1").addNames("v1").setRequiredMatch(true))
                .setExtraKeys(
                    ExtraKeys.newBuilder()
                        .setHost("host1").setService("service1").setMethod("method1"))
                .putConstantKeys("key2", "value2"))
        .setLookupService("rls-cbt.googleapis.com")
        .setLookupServiceTimeout(Durations.fromMillis(1234))
        .setMaxAge(Durations.fromMillis(56789))
        .setStaleAge(Durations.fromMillis(1000))
        .setCacheSizeBytes(5000)
        .addValidTargets("valid-target")
        .setDefaultTarget("default-target")
        .build();
    RlsPluginConfig config =
        RouteLookupServiceClusterSpecifierPlugin.INSTANCE.parsePlugin(Any.pack(routeLookupConfig))
            .config;
    assertThat(config.typeUrl()).isEqualTo("type.googleapis.com/grpc.lookup.v1.RouteLookupConfig");
    assertThat(config.config()).isEqualTo(
        new RlsProtoData.RouteLookupConfig(
            ImmutableList.of(
                new RlsProtoData.GrpcKeyBuilder(
                    ImmutableList.of(
                        new RlsProtoData.GrpcKeyBuilder.Name("service1", "method1"),
                        new RlsProtoData.GrpcKeyBuilder.Name("service2", "method2")),
                    ImmutableList.of(
                        new RlsProtoData.NameMatcher("key1", ImmutableList.of("v1"), true)),
                    RlsProtoData.ExtraKeys.create("host1", "service1", "method1"),
                    ImmutableMap.of("key2", "value2")
                )),
            "rls-cbt.googleapis.com",
            1234,
            56789L,
            1000L,
            5000,
            ImmutableList.of("valid-target"),
            "default-target"));
  }

  @Test
  public void parseConfigWithOptionalFieldsUnspecified() {
    RouteLookupConfig routeLookupConfig = RouteLookupConfig.newBuilder()
        .addGrpcKeybuilders(
            GrpcKeyBuilder.newBuilder()
                .addNames(Name.newBuilder().setService("service1"))
                .addNames(Name.newBuilder().setService("service2"))
                .addHeaders(
                    NameMatcher.newBuilder().setKey("key1").addNames("v1").setRequiredMatch(true)))
        .setLookupService("rls-cbt.googleapis.com")
        .setLookupServiceTimeout(Durations.fromMillis(1234))
        .setCacheSizeBytes(5000)
        .addValidTargets("valid-target")
        .build();
    RlsPluginConfig config =
        RouteLookupServiceClusterSpecifierPlugin.INSTANCE.parsePlugin(Any.pack(routeLookupConfig))
            .config;
    assertThat(config.typeUrl()).isEqualTo("type.googleapis.com/grpc.lookup.v1.RouteLookupConfig");
    assertThat(config.config()).isEqualTo(
        new RlsProtoData.RouteLookupConfig(
            ImmutableList.of(
                new RlsProtoData.GrpcKeyBuilder(
                    ImmutableList.of(
                        new RlsProtoData.GrpcKeyBuilder.Name("service1"),
                        new RlsProtoData.GrpcKeyBuilder.Name("service2")),
                    ImmutableList.of(
                        new RlsProtoData.NameMatcher("key1", ImmutableList.of("v1"), true)),
                    RlsProtoData.ExtraKeys.create(null, null, null),
                    ImmutableMap.<String, String>of()
                )),
            "rls-cbt.googleapis.com",
            1234,
            null,
            null,
            5000,
            ImmutableList.of("valid-target"),
            null));
  }

  @Test
  public void parseInvalidConfig() {
    RouteLookupConfig routeLookupConfig = RouteLookupConfig.newBuilder()
        .addGrpcKeybuilders(
            GrpcKeyBuilder.newBuilder()
                .addNames(Name.newBuilder().setService("service1"))
                .addNames(Name.newBuilder().setService("service2"))
                .addHeaders(
                    NameMatcher.newBuilder().setKey("key1").addNames("v1").setRequiredMatch(true)))
        .setLookupService("rls-cbt.googleapis.com")
        .setLookupServiceTimeout(Durations.fromMillis(1234))
        .setCacheSizeBytes(-5000) // negative
        .addValidTargets("valid-target")
        .build();
    ConfigOrError<RlsPluginConfig> configOrError =
        RouteLookupServiceClusterSpecifierPlugin.INSTANCE.parsePlugin(Any.pack(routeLookupConfig));
    assertThat(configOrError.errorDetail).contains("cacheSize must be positive");
  }
}
