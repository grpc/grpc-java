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

package io.grpc.inprocess;

import static org.junit.Assert.assertEquals;

import io.grpc.Status;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class InProcessTransportTestWithCause extends InProcessTransportTest {

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    InProcessTransport transport = (InProcessTransport) super.newClientTransport(server);
    transport.includeStatusWithCause(true);
    return transport;
  }

  @Override
  protected void checkClientStatus(Status expectedStatus, Status clientStreamStatus) {
    assertEquals(expectedStatus.getCode(), clientStreamStatus.getCode());
    assertEquals(expectedStatus.getDescription(), clientStreamStatus.getDescription());
    // Transport has been configured to pass the cause
    assertEquals(expectedStatus.getCause(), clientStreamStatus.getCause());
  }
}
