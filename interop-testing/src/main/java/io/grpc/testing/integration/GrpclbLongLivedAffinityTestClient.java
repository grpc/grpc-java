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

package io.grpc.testing.integration;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.alts.ComputeEngineChannelBuilder;
import io.grpc.testing.integration.Messages.Payload;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Test client that verifies all requests are sent to the same server even running for an extended
 * time, while allowing for occasionally switching server.  This is intended for testing the GRPCLB
 * pick_first mode in GCE.
 */
public final class GrpclbLongLivedAffinityTestClient {
  private static final Logger logger =
      Logger.getLogger(GrpclbLongLivedAffinityTestClient.class.getName());

  /**
   * Entry point.
   */
  public static void main(String[] args) throws Exception {
    final GrpclbLongLivedAffinityTestClient client = new GrpclbLongLivedAffinityTestClient();
    client.parseArgs(args);
    client.setUp();

    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      @SuppressWarnings("CatchAndPrintStackTrace")
      public void run() {
        try {
          client.shutdown();
        } catch (Exception e) {
          // At this moment logger may have stopped working
          e.printStackTrace();
        }
      }
    });

    try {
      client.run();
    } finally {
      client.shutdown();
    }
  }

  private String target = "directpath-grpclb-with-pick-first-test.googleapis.com";
  private long rpcErrorBudgetIncreaseMinutes = 2;
  private long affinityBreakageBudgetIncreaseMinutes = 90;
  private long rpcIntermissionSeconds = 1;
  private long totalTestSeconds = 60;

  protected ManagedChannel channel;
  protected TestServiceGrpc.TestServiceBlockingStub blockingStub;

  private void parseArgs(String[] args) {
    boolean usage = false;
    for (String arg : args) {
      if (!arg.startsWith("--")) {
        System.err.println("All arguments must start with '--': " + arg);
        usage = true;
        break;
      }
      String[] parts = arg.substring(2).split("=", 2);
      String key = parts[0];
      if ("help".equals(key)) {
        usage = true;
        break;
      }
      if (parts.length != 2) {
        System.err.println("All arguments must be of the form --arg=value");
        usage = true;
        break;
      }
      String value = parts[1];
      if ("target".equals(key)) {
        target = value;
      } else if ("rpc_error_budget_increase_minutes".equals(key)) {
        rpcErrorBudgetIncreaseMinutes = Long.parseLong(value);
      } else if ("affinity_breakage_budget_increase_minutes".equals(key)) {
        affinityBreakageBudgetIncreaseMinutes = Long.parseLong(value);
      } else if ("rpc_intermission_seconds".equals(key)) {
        rpcIntermissionSeconds = Long.parseLong(value);
      } else if ("total_test_seconds".equals(key)) {
        totalTestSeconds = Long.parseLong(value);
      } else {
        System.err.println("Unknown argument: " + key);
        usage = true;
        break;
      }
    }
    if (usage) {
      GrpclbLongLivedAffinityTestClient c = new GrpclbLongLivedAffinityTestClient();
      System.out.println(
          "Usage: [ARGS...]"
          + "\n"
          + "\n  --target=TARGET          Server target.             Default " + c.target
          + "\n  --rpc_error_budget_increase_minutes=MINUTES         Default "
          + c.rpcErrorBudgetIncreaseMinutes
          + "\n  --affinity_breakage_budget_increase_minutes=MINUTES Default "
          + c.affinityBreakageBudgetIncreaseMinutes
          + "\n  --rpc_intermission_seconds=SECONDS                  Default "
          + c.rpcIntermissionSeconds
          + "\n  --total_test_seconds=SECONDS                        Default "
          + c.totalTestSeconds
      );
      System.exit(1);
    }
  }

  private void setUp() {
    channel = createChannel();
    blockingStub = TestServiceGrpc.newBlockingStub(channel);
  }

  private void shutdown() {
    try {
      if (channel != null) {
        channel.shutdownNow();
        channel.awaitTermination(1, TimeUnit.SECONDS);
      }
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private void run() throws Exception {
    final long startTimeMillis = System.currentTimeMillis();
    final long endTimeMillis = startTimeMillis + TimeUnit.SECONDS.toMillis(totalTestSeconds);
    final long rpcIntermissionMillis = TimeUnit.SECONDS.toMillis(rpcIntermissionSeconds);
    final long rpcErrorBudgetIncreasePeriodMillis =
        TimeUnit.MINUTES.toMillis(rpcErrorBudgetIncreaseMinutes);
    final long affinityBreakageBudgetIncreasePeriodMillis =
        TimeUnit.MINUTES.toMillis(affinityBreakageBudgetIncreaseMinutes);
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setResponseSize(314159)
        .setFillServerId(true)
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[271828])))
        .build();
    String lastServerId = null;
    long rpcErrorBudget = 1;
    long affinityBreakageBudget = 1;
    long lastRpcErrorBudgetIncreaseTimeMillis = startTimeMillis;
    long lastAffinityBreakageBudgetIncreaseTimeMillis = startTimeMillis;

    logger.info("Test started");

    while (true) {
      try {
        logger.info("Sending request");
        SimpleResponse response =
            blockingStub.withDeadlineAfter(1, TimeUnit.MINUTES).unaryCall(request);
        logger.info("Received response");
        String serverId = response.getServerId();
        checkNotNull(serverId, "serverId is null");
        if (lastServerId != null && !lastServerId.equals(serverId)) {
          String msg = "Expected serverId " + lastServerId + ", but got " + serverId;
          logger.warning(msg + ". affinityBreakageBudget=" + affinityBreakageBudget);
          affinityBreakageBudget--;
          if (affinityBreakageBudget < 0) {
            throw new AssertionError(msg);
          }
        }
      } catch (StatusRuntimeException e) {
        logger.log(Level.WARNING, "RPC error. rpcErrorBudget=" + rpcErrorBudget, e);
        rpcErrorBudget--;
        if (rpcErrorBudget < 0) {
          throw e;
        }
      }
      Thread.sleep(rpcIntermissionMillis);
      long nowMillis = System.currentTimeMillis();
      if (nowMillis > endTimeMillis) {
        logger.info("Time is up");
        break;
      }
      if (nowMillis > lastRpcErrorBudgetIncreaseTimeMillis + rpcErrorBudgetIncreasePeriodMillis) {
        lastRpcErrorBudgetIncreaseTimeMillis = nowMillis;
        rpcErrorBudget = Math.min(20, rpcErrorBudget + 1);
        logger.info("rpcErrorBudget after refresh: " + rpcErrorBudget);
      }
      if (nowMillis > lastAffinityBreakageBudgetIncreaseTimeMillis
          + affinityBreakageBudgetIncreasePeriodMillis) {
        lastAffinityBreakageBudgetIncreaseTimeMillis = nowMillis;
        affinityBreakageBudget = Math.min(3, affinityBreakageBudget + 1);
        logger.info("affinityBreakageBudget after refresh: " + affinityBreakageBudget);
      }
    }

    logger.info("Test passed.");
  }

  private ManagedChannel createChannel() {
    return ComputeEngineChannelBuilder.forTarget(target).build();
  }
}

