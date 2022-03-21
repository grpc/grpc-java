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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Metadata;
import io.grpc.rls.RlsProtoData.ExtraKeys;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder.Name;
import io.grpc.rls.RlsProtoData.NameMatcher;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RlsRequestFactoryTest {

  private static final RouteLookupConfig RLS_CONFIG =
      RouteLookupConfig.builder()
          .grpcKeybuilders(ImmutableList.of(
              GrpcKeyBuilder.create(
                  ImmutableList.of(Name.create("com.google.service1", "Create")),
                  ImmutableList.of(
                      NameMatcher.create("user", ImmutableList.of("User", "Parent")),
                      NameMatcher.create("id", ImmutableList.of("X-Google-Id"))),
                  ExtraKeys.create("server-1", null, null),
                  ImmutableMap.of("const-key-1", "const-value-1")),
              GrpcKeyBuilder.create(
                  ImmutableList.of(Name.create("com.google.service1", "*")),
                  ImmutableList.of(
                      NameMatcher.create("user", ImmutableList.of("User", "Parent")),
                      NameMatcher.create("password", ImmutableList.of("Password"))),
                  ExtraKeys.create(null, "service-2", null),
                  ImmutableMap.of("const-key-2", "const-value-2")),
              GrpcKeyBuilder.create(
                  ImmutableList.of(Name.create("com.google.service2", "*")),
                  ImmutableList.of(
                      NameMatcher.create("password", ImmutableList.of("Password"))),
                  ExtraKeys.create(null, "service-3", "method-3"),
                  ImmutableMap.<String, String>of()),
              GrpcKeyBuilder.create(
                  ImmutableList.of(Name.create("com.google.service3", "*")),
                  ImmutableList.of(
                      NameMatcher.create("user", ImmutableList.of("User", "Parent"))),
                  ExtraKeys.create(null, null, null),
                  ImmutableMap.of("const-key-4", "const-value-4"))))
          .lookupService("bigtable-rls.googleapis.com")
          .lookupServiceTimeoutInNanos(TimeUnit.SECONDS.toNanos(2))
          .maxAgeInNanos(TimeUnit.SECONDS.toNanos(300))
          .staleAgeInNanos(TimeUnit.SECONDS.toNanos(240))
          .cacheSizeBytes(1000)
          .defaultTarget("us_east_1.cloudbigtable.googleapis.com")
          .build();

  private final RlsRequestFactory factory = new RlsRequestFactory(
      RLS_CONFIG, "bigtable.googleapis.com");

  @Test
  public void create_pathMatches() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "123");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("com.google.service1", "Create", metadata);
    assertThat(request.keyMap()).containsExactly(
        "user", "test",
        "id", "123",
        "server-1", "bigtable.googleapis.com",
        "const-key-1", "const-value-1");
  }

  @Test
  public void create_pathFallbackMatches() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("Parent", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("Password", Metadata.ASCII_STRING_MARSHALLER), "hunter2");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("com.google.service1" , "Update", metadata);

    assertThat(request.keyMap()).containsExactly(
        "user", "test",
        "password", "hunter2",
        "service-2", "com.google.service1",
        "const-key-2", "const-value-2");
  }

  @Test
  public void create_pathFallbackMatches_optionalHeaderMissing() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "123");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("com.google.service1", "Update", metadata);

    assertThat(request.keyMap()).containsExactly(
        "user", "test",
        "service-2", "com.google.service1",
        "const-key-2", "const-value-2");
  }

  @Test
  public void create_unknownPath() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "123");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("abc.def.service999", "Update", metadata);
    assertThat(request.keyMap()).isEmpty();
  }

  @Test
  public void create_noMethodInRlsConfig() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "123");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("com.google.service3", "Update", metadata);

    assertThat(request.keyMap()).containsExactly(
        "user", "test", "const-key-4", "const-value-4");
  }
}
