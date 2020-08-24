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

import com.google.common.collect.ImmutableList;
import io.grpc.Metadata;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
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
      new RouteLookupConfig(
          ImmutableList.of(
              new GrpcKeyBuilder(
                  ImmutableList.of(new Name("com.google.service1", "Create")),
                  ImmutableList.of(
                      new NameMatcher("user", ImmutableList.of("User", "Parent"), true),
                      new NameMatcher("id", ImmutableList.of("X-Google-Id"), true))),
              new GrpcKeyBuilder(
                  ImmutableList.of(new Name("com.google.service1")),
                  ImmutableList.of(
                      new NameMatcher("user", ImmutableList.of("User", "Parent"), true),
                      new NameMatcher("password", ImmutableList.of("Password"), true))),
              new GrpcKeyBuilder(
                  ImmutableList.of(new Name("com.google.service2")),
                  ImmutableList.of(
                      new NameMatcher("user", ImmutableList.of("User", "Parent"), false),
                      new NameMatcher("password", ImmutableList.of("Password"), true))),
              new GrpcKeyBuilder(
                  ImmutableList.of(new Name("com.google.service3")),
                  ImmutableList.of(
                      new NameMatcher("user", ImmutableList.of("User", "Parent"), true)))),
          /* lookupService= */ "foo.google.com",
          /* lookupServiceTimeoutInMillis= */ TimeUnit.SECONDS.toMillis(2),
          /* maxAgeInMillis= */ TimeUnit.SECONDS.toMillis(300),
          /* staleAgeInMillis= */ TimeUnit.SECONDS.toMillis(240),
          /* cacheSizeBytes= */ 1000,
          /* validTargets= */ ImmutableList.of("a valid target"),
          /* defaultTarget= */ "us_east_1.cloudbigtable.googleapis.com");

  private final RlsRequestFactory factory = new RlsRequestFactory(RLS_CONFIG);

  @Test
  public void create_pathMatches() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "123");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("com.google.service1", "Create", metadata);
    assertThat(request.getTargetType()).isEqualTo("grpc");
    assertThat(request.getPath()).isEqualTo("/com.google.service1/Create");
    assertThat(request.getServer()).isEqualTo("foo.google.com");
    assertThat(request.getKeyMap()).containsExactly("user", "test", "id", "123");
  }

  @Test
  public void create_missingRequiredHeader() {
    Metadata metadata = new Metadata();

    try {
      RouteLookupRequest unused = factory.create("com.google.service2", "Create", metadata);
      fail();
    } catch (StatusRuntimeException e) {
      assertThat(e.getStatus().getCode()).isEqualTo(Code.INVALID_ARGUMENT);
      assertThat(e.getStatus().getDescription())
          .isEqualTo("Missing mandatory metadata(user) not found");
    }
  }

  @Test
  public void create_pathFallbackMatches() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("Parent", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("Password", Metadata.ASCII_STRING_MARSHALLER), "hunter2");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("com.google.service1" , "Update", metadata);

    assertThat(request.getTargetType()).isEqualTo("grpc");
    assertThat(request.getPath()).isEqualTo("/com.google.service1/Update");
    assertThat(request.getServer()).isEqualTo("foo.google.com");
    assertThat(request.getKeyMap()).containsExactly("user", "test", "password", "hunter2");
  }

  @Test
  public void create_pathFallbackMatches_optionalHeaderMissing() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "123");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("com.google.service1", "Update", metadata);

    assertThat(request.getTargetType()).isEqualTo("grpc");
    assertThat(request.getPath()).isEqualTo("/com.google.service1/Update");
    assertThat(request.getServer()).isEqualTo("foo.google.com");
    assertThat(request.getKeyMap()).containsExactly("user", "test");
  }

  @Test
  public void create_unknownPath() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "123");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("abc.def.service999", "Update", metadata);

    assertThat(request.getTargetType()).isEqualTo("grpc");
    assertThat(request.getPath()).isEqualTo("/abc.def.service999/Update");
    assertThat(request.getServer()).isEqualTo("foo.google.com");
    assertThat(request.getKeyMap()).isEmpty();
  }

  @Test
  public void create_noMethodInRlsConfig() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "123");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("com.google.service3", "Update", metadata);

    assertThat(request.getTargetType()).isEqualTo("grpc");
    assertThat(request.getPath()).isEqualTo("/com.google.service3/Update");
    assertThat(request.getServer()).isEqualTo("foo.google.com");
    assertThat(request.getKeyMap()).containsExactly("user", "test");
  }
}
