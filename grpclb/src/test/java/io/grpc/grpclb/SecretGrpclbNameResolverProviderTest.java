/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.grpclb;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.internal.BaseDnsNameResolverProvider.ENABLE_GRPCLB_PROPERTY_NAME;
import static org.junit.Assume.assumeTrue;

import io.grpc.internal.DnsNameResolverProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SecretGrpclbNameResolverProviderTest {

  @Test
  public void priority_shouldBeHigherThanDefaultDnsNameResolver() {
    DnsNameResolverProvider defaultDnsNameResolver = new DnsNameResolverProvider();
    SecretGrpclbNameResolverProvider.Provider grpclbDnsNameResolver =
        new SecretGrpclbNameResolverProvider.Provider();

    assertThat(defaultDnsNameResolver.priority())
        .isLessThan(grpclbDnsNameResolver.priority());
  }

  @Test
  public void isSrvEnabled_trueByDefault() {
    assumeTrue(System.getProperty(ENABLE_GRPCLB_PROPERTY_NAME) == null);

    SecretGrpclbNameResolverProvider.Provider grpclbDnsNameResolver =
        new SecretGrpclbNameResolverProvider.Provider();

    assertThat(grpclbDnsNameResolver.isSrvEnabled()).isTrue();
  }
}