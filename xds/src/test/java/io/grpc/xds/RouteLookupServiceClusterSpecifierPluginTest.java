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
import io.grpc.lookup.v1.RouteLookupClusterSpecifier;
import io.grpc.lookup.v1.RouteLookupConfig;
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
    RouteLookupClusterSpecifier specifier =
        RouteLookupClusterSpecifier.newBuilder().setRouteLookupConfig(routeLookupConfig).build();
    RlsPluginConfig config =
        RouteLookupServiceClusterSpecifierPlugin.INSTANCE.parsePlugin(Any.pack(specifier))
            .config;
    assertThat(config.typeUrl())
        .isEqualTo("type.googleapis.com/grpc.lookup.v1.RouteLookupClusterSpecifier");
    assertThat(config.config()).isEqualTo(
        ImmutableMap.builder()
            .put(
                "grpcKeybuilders",
                ImmutableList.of(ImmutableMap.of(
                    "names",
                    ImmutableList.of(
                        ImmutableMap.of("service", "service1", "method", "method1"),
                        ImmutableMap.of("service", "service2", "method", "method2")),
                    "headers",
                    ImmutableList.of(
                        ImmutableMap.of(
                            "key", "key1", "names", ImmutableList.of("v1"),
                            "requiredMatch", true)),
                    "extraKeys",
                    ImmutableMap.of("host", "host1", "service", "service1", "method", "method1"),
                    "constantKeys",
                    ImmutableMap.of("key2", "value2"))))
            .put("lookupService", "rls-cbt.googleapis.com")
            .put("lookupServiceTimeout", "1.234s")
            .put("maxAge", "56.789s")
            .put("staleAge", "1s")
            .put("cacheSizeBytes", "5000")
            .put("validTargets", ImmutableList.of("valid-target"))
            .put("defaultTarget","default-target")
            .buildOrThrow());
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
    RouteLookupClusterSpecifier specifier =
        RouteLookupClusterSpecifier.newBuilder().setRouteLookupConfig(routeLookupConfig).build();
    RlsPluginConfig config =
        RouteLookupServiceClusterSpecifierPlugin.INSTANCE.parsePlugin(Any.pack(specifier))
            .config;
    assertThat(config.typeUrl())
        .isEqualTo("type.googleapis.com/grpc.lookup.v1.RouteLookupClusterSpecifier");
    assertThat(config.config()).isEqualTo(
        ImmutableMap.builder()
            .put(
                "grpcKeybuilders",
                ImmutableList.of(ImmutableMap.of(
                    "names",
                    ImmutableList.of(
                        ImmutableMap.of("service", "service1"),
                        ImmutableMap.of("service", "service2")),
                    "headers",
                    ImmutableList.of(
                        ImmutableMap.of(
                            "key", "key1", "names", ImmutableList.of("v1"),
                            "requiredMatch", true)))))
            .put("lookupService", "rls-cbt.googleapis.com")
            .put("lookupServiceTimeout", "1.234s")
            .put("cacheSizeBytes", "5000")
            .put("validTargets", ImmutableList.of("valid-target"))
            .buildOrThrow());
  }
}
