/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.NameResolverProvider;
import io.grpc.grpclb.SecretGrpclbDnsNameResolverProvider.GrpclbDnsNameResolverProvider;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SecretGrpclbDnsNameResolverProviderTest {

  @BeforeClass
  public static void setUpStatic() {
    SecretGrpclbDnsNameResolverProvider.enableBalancerLookup = true;
  }

  private final GrpclbDnsNameResolverProvider resolver = new GrpclbDnsNameResolverProvider();

  @Test
  public void available() {
    assertTrue(resolver.isAvailable());
  }

  @Test
  public void scheme() {
    assertEquals("dns", resolver.getDefaultScheme());
  }

  @Test
  public void classLoaderWorks() {
    for (NameResolverProvider res : NameResolverProvider.providers()) {
      if (res instanceof GrpclbDnsNameResolverProvider) {
        return;
      }
    }
    fail("unable to find resolver");
  }
}