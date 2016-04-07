/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.testing.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.grpc.ManagedChannel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.testing.TestUtils;
import io.grpc.testing.integration.Metrics.EmptyMessage;
import io.grpc.testing.integration.Metrics.GaugeResponse;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Unit tests for {@link StressTestClient}. */
@RunWith(JUnit4.class)
public class StressTestClientTest {

  @Test(timeout = 5000)
  public void gaugesShouldBeExported() throws Exception {
    final int serverPort = TestUtils.pickUnusedPort();
    final int metricsPort = TestUtils.pickUnusedPort();

    TestServiceServer server = new TestServiceServer();
    server.parseArgs(new String[]{"--port=" + serverPort, "--use_tls=false"});
    server.start();

    StressTestClient client = new StressTestClient();
    client.parseArgs(new String[] {"--test_cases=empty_unary:1",
        "--server_addresses=localhost:" + serverPort, "--metrics_port=" + metricsPort,
        "--num_stubs_per_channel=2"});
    client.startMetricsService();
    client.runStressTest();

    // Connect to the metrics service
    ManagedChannel ch =
        NettyChannelBuilder.forAddress(new InetSocketAddress("localhost", metricsPort))
        .negotiationType(NegotiationType.PLAINTEXT)
        .build();

    MetricsServiceGrpc.MetricsServiceBlockingStub stub = MetricsServiceGrpc.newBlockingStub(ch);

    // Wait until gauges have been exported
    Iterator<GaugeResponse> responseIt = stub.getAllGauges(EmptyMessage.getDefaultInstance());
    while (!responseIt.hasNext()) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
      responseIt = stub.getAllGauges(EmptyMessage.getDefaultInstance());
    }

    int gaugesCount = 0;
    while (responseIt.hasNext()) {
      GaugeResponse response = responseIt.next();
      assertNotNull(response.getName());
      assertTrue("qps: " + response.getLongValue(), response.getLongValue() > 0);

      GaugeResponse response1 =
          stub.getGauge(Metrics.GaugeRequest.newBuilder().setName(response.getName()).build());
      assertEquals(response.getName(), response1.getName());
      assertTrue("qps: " + response1.getLongValue(), response1.getLongValue() > 0);

      gaugesCount++;
    }

    // Because 1 server, 1 channel and 2 stubs
    assertEquals(2, gaugesCount);

    client.shutdown();
    server.stop();
  }

}
