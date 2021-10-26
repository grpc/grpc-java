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

package io.grpc.rls;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.rls.RlsProtoData.ExtraKeys;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder.Name;
import io.grpc.rls.RlsProtoData.NameMatcher;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RlsProtoData}. */
@RunWith(JUnit4.class)
public class RlsProtoDataTest {
  @Test
  public void maxCacheSize() {
    RouteLookupConfig config = new RouteLookupConfig(
        ImmutableList.of(
            new GrpcKeyBuilder(
                ImmutableList.of(new Name("service1", "create")),
                ImmutableList.of(
                    new NameMatcher("user", ImmutableList.of("User", "Parent"), true),
                    new NameMatcher("id", ImmutableList.of("X-Google-Id"), true)),
                ExtraKeys.create("server", "service-key", "method-key"),
                ImmutableMap.<String, String>of())),
        "service1",
        /* lookupServiceTimeoutInMillis= */ TimeUnit.SECONDS.toMillis(2),
        /* maxAgeInMillis= */ TimeUnit.SECONDS.toMillis(300),
        /* staleAgeInMillis= */ TimeUnit.SECONDS.toMillis(240),
        /* cacheSizeBytes= */ 20 * 1000 * 1000,
        ImmutableList.of("a-valid-target"),
        "default-target");
    assertThat(config.getCacheSizeBytes()).isEqualTo(5 * 1024 * 1024);
  }
}
