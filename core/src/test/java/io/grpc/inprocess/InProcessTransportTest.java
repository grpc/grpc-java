/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.inprocess;

import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.testing.AbstractTransportTest;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link InProcessTransport}. */
@RunWith(JUnit4.class)
public class InProcessTransportTest extends AbstractTransportTest {
  private static final String transportName = "perfect-for-testing";
  private static final String authority = "a-testing-authority";

  @Override
  protected InternalServer newServer() {
    return new InProcessServer(transportName);
  }

  @Override
  protected InternalServer newServer(InternalServer server) {
    return newServer();
  }

  @Override
  protected String testAuthority(InternalServer server) {
    return authority;
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    return new InProcessTransport(transportName, testAuthority(server));
  }
}
