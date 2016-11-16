/*
 * Copyright 2016, gRPC Authors All rights reserved.
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

import io.grpc.ServerStreamTracer;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.testing.AbstractTransportTest;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.AfterClass;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link InProcessTransport}. */
@RunWith(JUnit4.class)
public class InProcessTransportTest extends AbstractTransportTest {
  private static final String TRANSPORT_NAME = "perfect-for-testing";
  private static final String AUTHORITY = "a-testing-authority";

  private static final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor();

  @AfterClass
  public static void shutdown() {
    scheduler.shutdown();
  }

  @Override
  protected InternalServer newServer(List<ServerStreamTracer.Factory> streamTracerFactories) {
    return new InProcessServer(TRANSPORT_NAME);
  }

  @Override
  protected InternalServer newServer(
      InternalServer server, List<ServerStreamTracer.Factory> streamTracerFactories) {
    return newServer(streamTracerFactories);
  }

  @Override
  protected String testAuthority(InternalServer server) {
    return AUTHORITY;
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    return new InProcessTransport(TRANSPORT_NAME, testAuthority(server), scheduler);
  }

  @Override
  protected boolean metricsExpected() {
    // TODO(zhangkun83): InProcessTransport doesn't record metrics for now
    // (https://github.com/grpc/grpc-java/issues/2284)
    return false;
  }
}
