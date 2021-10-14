/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.inprocess;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.testing.EqualsTester;
import io.grpc.ServerStreamTracer;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class InProcessSocketAddressTest {

  @Test
  public void equal() {
    new EqualsTester()
        .addEqualityGroup(
            new InProcessSocketAddress("a"),
            new InProcessSocketAddress(new String("a")))
        .addEqualityGroup(
            new InProcessSocketAddress("z"),
            new InProcessSocketAddress("z"))
        .addEqualityGroup(
            new InProcessSocketAddress(""),
            new InProcessSocketAddress("")
        )
        .testEquals();
  }

  @Test
  public void equalAnonymousServer() {
    InProcessServer anonServer = createAnonymousServer();
    InProcessSocketAddress anonAddr = new InProcessSocketAddress("a", anonServer);
    new EqualsTester()
        .addEqualityGroup(
            anonAddr,
            anonAddr)
        .addEqualityGroup(
            new InProcessSocketAddress("a"))
        .addEqualityGroup(
            new InProcessSocketAddress("a", anonServer))
        .testEquals();
  }

  @Test
  public void hash() {
    assertThat(new InProcessSocketAddress("a").hashCode())
        .isEqualTo(new InProcessSocketAddress(new String("a")).hashCode());

    InProcessServer anonServer = createAnonymousServer();
    assertThat(new InProcessSocketAddress("a", anonServer).hashCode())
        .isNotEqualTo(new InProcessSocketAddress("a", anonServer).hashCode());
  }


  private InProcessServer createAnonymousServer() {
    InProcessServerBuilder builder = InProcessServerBuilder.anonymous();
    return new InProcessServer(builder, Collections.<ServerStreamTracer.Factory>emptyList());
  }
}
