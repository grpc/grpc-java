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

package io.grpc.android.integrationtest;

import static org.junit.Assert.assertEquals;

import android.net.LocalSocketAddress.Namespace;
import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import io.grpc.Server;
import io.grpc.android.UdsChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.testing.integration.TestServiceImpl;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for channels created with {@link UdsChannelBuilder}. The UDS Channel is only meant to talk
 * to Unix Domain Socket endpoints on servers that are on-device, so a {@link LocalTestServer} is
 * set up to expose a UDS endpoint.
 */
@RunWith(AndroidJUnit4.class)
public class UdsChannelInteropTest {
  private static final int TIMEOUT_SECONDS = 150;

  private static final String UDS_PATH = "udspath";
  private String testCase;

  private Server server;
  private UdsTcpEndpointConnector endpointConnector;

  private ScheduledExecutorService serverExecutor = Executors.newScheduledThreadPool(2);
  private ExecutorService testExecutor = Executors.newSingleThreadExecutor();

  // Ensures Looper is initialized for tests running on API level 15. Otherwise instantiating an
  // AsyncTask throws an exception.
  @Rule
  public ActivityTestRule<TesterActivity> activityRule =
      new ActivityTestRule<TesterActivity>(TesterActivity.class);

  @Before
  public void setUp() throws IOException {
    testCase = InstrumentationRegistry.getArguments().getString("test_case", "all");

    // Start local server.
    server =
        NettyServerBuilder.forPort(0)
            .maxInboundMessageSize(16 * 1024 * 1024)
            .addService(new TestServiceImpl(serverExecutor))
            .build();
    server.start();

    // Connect uds endpoint to server's endpoint.
    endpointConnector = new UdsTcpEndpointConnector(UDS_PATH, "0.0.0.0", server.getPort());
    endpointConnector.start();
  }

  @After
  public void teardown() {
    server.shutdownNow();
    endpointConnector.shutDown();
  }

  @Test
  public void interopTests() throws Exception {
    if (testCase.equals("all")) {
      runTest("empty_unary");
      runTest("large_unary");
      runTest("client_streaming");
      runTest("server_streaming");
      runTest("ping_pong");
      runTest("empty_stream");
      runTest("cancel_after_begin");
      runTest("cancel_after_first_response");
      runTest("full_duplex_call_should_succeed");
      runTest("half_duplex_call_should_succeed");
      runTest("server_streaming_should_be_flow_controlled");
      runTest("very_large_request");
      runTest("very_large_response");
      runTest("deadline_not_exceeded");
      runTest("deadline_exceeded");
      runTest("deadline_exceeded_server_streaming");
      runTest("unimplemented_method");
      runTest("timeout_on_sleeping_server");
      runTest("graceful_shutdown");
    } else {
      runTest(testCase);
    }
  }

  private void runTest(String testCase) throws Exception {
    String result = null;
    try {
      result = testExecutor.submit(new TestCallable(
              UdsChannelBuilder.forPath(UDS_PATH, Namespace.ABSTRACT)
                      .maxInboundMessageSize(16 * 1024 * 1024)
                      .build(),
              testCase)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(testCase + " failed", TestCallable.SUCCESS_MESSAGE, result);
    } catch (ExecutionException | InterruptedException e) {
      result = e.getMessage();
    }
  }
}
