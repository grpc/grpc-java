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

package io.grpc.inprocess;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.testing.EqualsTester;
import io.grpc.ServerStreamTracer;
import java.io.IOException;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AnonymousInProcessSocketAddress}. */
@RunWith(JUnit4.class)
public class AnonymousInProcessSocketAddressTest {

  @Test
  public void defaultState() {
    AnonymousInProcessSocketAddress addr = new AnonymousInProcessSocketAddress();
    assertThat(addr.getServer()).isNull();
  }

  @Test
  public void setServer() throws Exception {
    AnonymousInProcessSocketAddress addr = new AnonymousInProcessSocketAddress();
    InProcessServer server = createAnonymousServer();
    addr.setServer(server);
    assertThat(addr.getServer()).isSameInstanceAs(server);
  }

  @Test
  public void setServerTwice() throws Exception {
    AnonymousInProcessSocketAddress addr = new AnonymousInProcessSocketAddress();
    InProcessServer server = createAnonymousServer();
    addr.setServer(server);
    try {
      addr.setServer(server);
      fail("Expected IOException on attempt to set server twice");
    } catch (IOException ioe) {
      // Expected.
    }
  }

  @Test
  public void clearServer() throws Exception {
    AnonymousInProcessSocketAddress addr = new AnonymousInProcessSocketAddress();
    InProcessServer server = createAnonymousServer();
    addr.setServer(server);
    addr.clearServer(server);
    assertThat(addr.getServer()).isNull();
  }

  @Test
  public void clearServerWrongInstance() throws Exception {
    AnonymousInProcessSocketAddress addr = new AnonymousInProcessSocketAddress();
    addr.setServer(createAnonymousServer());
    try {
      addr.clearServer(createAnonymousServer());
      fail("Expected IllegalStateException on attempt to clear the wrong server");
    } catch (IllegalStateException ise) {
      // Expected.
    }
  }

  @Test
  public void equality() throws IOException {
    AnonymousInProcessSocketAddress addrA = new AnonymousInProcessSocketAddress();
    AnonymousInProcessSocketAddress addrB = new AnonymousInProcessSocketAddress();
    AnonymousInProcessSocketAddress addrC = new AnonymousInProcessSocketAddress();
    InProcessServer server = createAnonymousServer();

    // Ensure two addresses with the same server are still distinct from each other.
    addrA.setServer(server);
    addrB.setServer(server);
    new EqualsTester()
        .addEqualityGroup(addrA)
        .addEqualityGroup(addrB)
        .addEqualityGroup(addrC)
        .testEquals();
  }

  private InProcessServer createAnonymousServer() {
    AnonymousInProcessSocketAddress unused = new AnonymousInProcessSocketAddress();
    InProcessServerBuilder builder = InProcessServerBuilder.forAddress(unused);
    return new InProcessServer(builder, Collections.<ServerStreamTracer.Factory>emptyList());
  }
}
