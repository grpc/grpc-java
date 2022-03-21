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

import static com.google.common.base.Charsets.UTF_8;
import static org.junit.Assert.assertEquals;

import com.google.common.io.CharStreams;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.alts.ComputeEngineChannelBuilder;
import io.grpc.testing.integration.Messages.GrpclbRouteType;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Test client that verifies that grpclb failover into fallback mode works under
 * different failure modes.
 * This client is suitable for testing fallback with any "grpclb" load-balanced
 * service, but is particularly meant to implement a set of test cases described
 * in an internal doc titled "DirectPath Cloud-to-Prod End-to-End Test Cases",
 * section "gRPC DirectPath-to-CFE fallback".
 */
public final class GrpclbFallbackTestClient {
  private static final Logger logger =
      Logger.getLogger(GrpclbFallbackTestClient.class.getName());

  /**
   * Entry point.
   */
  public static void main(String[] args) throws Exception {
    final GrpclbFallbackTestClient client = new GrpclbFallbackTestClient();
    client.parseArgs(args);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      @SuppressWarnings("CatchAndPrintStackTrace")
      public void run() {
        System.out.println("Shutting down");
        try {
          client.tearDown();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    });
    try {
      client.run();
    } finally {
      client.tearDown();
    }
    System.exit(0);
  }

  private String induceFallbackCmd = "exit 1";
  private String serverUri;
  private String customCredentialsType;
  private String testCase;
  private Boolean skipNetCmd = false;
  private int numWarmupRpcs;
  private int fallbackDeadlineSeconds = 1;

  private ManagedChannel channel;
  private TestServiceGrpc.TestServiceBlockingStub blockingStub;

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
      if ("server_uri".equals(key)) {
        serverUri = value;
      } else if ("test_case".equals(key)) {
        testCase = value;
      } else if ("induce_fallback_cmd".equals(key)) {
        induceFallbackCmd = value;
      } else if ("custom_credentials_type".equals(key)) {
        customCredentialsType = value;
      } else if ("skip_net_cmd".equals(key)) {
        skipNetCmd = Boolean.valueOf(value);
      } else if ("num_warmup_rpcs".equals(key)) {
        numWarmupRpcs = Integer.valueOf(value);
      } else if ("fallback_deadline_seconds".equals(key)) {
        fallbackDeadlineSeconds = Integer.valueOf(value);
      } else {
        System.err.println("Unknown argument: " + key);
        usage = true;
        break;
      }
    }
    if (usage) {
      GrpclbFallbackTestClient c = new GrpclbFallbackTestClient();
      System.out.println(
          "Usage: [ARGS...]"
          + "\n"
          + "\n  --server_uri                          Server target. Default: "
          + c.serverUri
          + "\n  --custom_credentials_type             Name of Credentials to use. "
          + "Default: " + c.customCredentialsType
          + "\n  --induce_fallback_cmd Shell command to induce fallback, e.g. by "
          + "making LB and/or backend addresses unroutable or black holed. Default: "
          + c.induceFallbackCmd
          + "\n  --skip_net_cmd                        Skip unroute and blackhole "
          + "shell command to allow setting the net config outside of the test "
          + "client. Default: "
          + c.skipNetCmd
          + "\n  --num_warmup_rpcs                     Number of RPCs to perform "
          + "on a separate warmup channel before the actual test runs (each warmup "
          + "RPC uses a 1 second deadline). Default: "
          + c.numWarmupRpcs
          + "\n  --fallback_deadline_seconds           Number of seconds to wait "
          + "for fallback to occur after inducing fallback. Default: "
          + c.fallbackDeadlineSeconds
          + "\n  --test_case=TEST_CASE        Test case to run. Valid options are:"
          + "\n      fallback_before_startup : fallback before startup e.g. due to "
          + "LB/backend addresses being unreachable"
          + "\n      fallback_after_startup : fallback after startup e.g. due to "
          + "LB/backend addresses becoming unreachable"
          + "\n      Default: " + c.testCase
      );
      System.exit(1);
    }
  }

  private ManagedChannel createChannel() {
    if (!customCredentialsType.equals("compute_engine_channel_creds")) {
      throw new AssertionError(
          "This test currently only supports "
          + "--custom_credentials_type=compute_engine_channel_creds. "
          + "TODO: add support for other types.");
    }
    ComputeEngineChannelBuilder builder = ComputeEngineChannelBuilder.forTarget(serverUri);
    builder.keepAliveTime(3600, TimeUnit.SECONDS);
    builder.keepAliveTimeout(20, TimeUnit.SECONDS);
    return builder.build();
  }

  void initStub() {
    channel = createChannel();
    blockingStub = TestServiceGrpc.newBlockingStub(channel);
  }

  private void tearDown() {
    try {
      if (channel != null) {
        channel.shutdownNow();
        channel.awaitTermination(1, TimeUnit.SECONDS);
      }
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private void runShellCmd(String cmd) throws Exception {
    if (skipNetCmd) {
      logger.info("Skip net cmd because --skip_net_cmd is set to true");
      return;
    }
    logger.info("Run shell command: " + cmd);
    // Do not use bash -c here as bash may not exist in a container
    ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));
    pb.redirectErrorStream(true);
    Process process = pb.start();
    logger.info("Shell command merged stdout and stderr: "
        + CharStreams.toString(
            new InputStreamReader(process.getInputStream(), UTF_8)));
    int exitCode = process.waitFor();
    logger.info("Shell command exit code: " + exitCode);
    assertEquals(0, exitCode);
  }

  private GrpclbRouteType doRpcAndGetPath(
      TestServiceGrpc.TestServiceBlockingStub stub, Deadline deadline) {
    logger.info("doRpcAndGetPath deadline: " + deadline);
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setFillGrpclbRouteType(true)
        .build();
    GrpclbRouteType result = GrpclbRouteType.GRPCLB_ROUTE_TYPE_UNKNOWN;
    try {
      SimpleResponse response = stub
          .withDeadline(deadline)
          .unaryCall(request);
      result = response.getGrpclbRouteType();
    } catch (StatusRuntimeException ex) {
      logger.warning("doRpcAndGetPath failed. Status: " + ex);
      return GrpclbRouteType.GRPCLB_ROUTE_TYPE_UNKNOWN;
    }
    logger.info("doRpcAndGetPath. GrpclbRouteType result: " + result);
    if (result != GrpclbRouteType.GRPCLB_ROUTE_TYPE_FALLBACK
        && result != GrpclbRouteType.GRPCLB_ROUTE_TYPE_BACKEND) {
      throw new AssertionError("Received invalid LB route type. This suggests "
          + "that the server hasn't implemented this test correctly.");
    }
    return result;
  }

  private void waitForFallbackAndDoRpcs(Deadline fallbackDeadline) throws Exception {
    int fallbackRetryCount = 0;
    boolean fallBack = false;
    while (!fallbackDeadline.isExpired()) {
      GrpclbRouteType grpclbRouteType = doRpcAndGetPath(
          blockingStub, Deadline.after(1, TimeUnit.SECONDS));
      if (grpclbRouteType == GrpclbRouteType.GRPCLB_ROUTE_TYPE_BACKEND) {
        throw new AssertionError("Got grpclb route type backend. Backends are "
            + "supposed to be unreachable, so this test is broken");
      }
      if (grpclbRouteType == GrpclbRouteType.GRPCLB_ROUTE_TYPE_FALLBACK) {
        logger.info("Made one successful RPC to a fallback. Now expect the "
            + "same for the rest.");
        fallBack = true;
        break;
      } else {
        logger.info("Retryable RPC failure on iteration: " + fallbackRetryCount);
      }
      fallbackRetryCount++;
    }
    if (!fallBack) {
      throw new AssertionError("Didn't fall back within deadline");
    }
    for (int i = 0; i < 30; i++) {
      assertEquals(
          GrpclbRouteType.GRPCLB_ROUTE_TYPE_FALLBACK,
          doRpcAndGetPath(blockingStub, Deadline.after(20, TimeUnit.SECONDS)));
      Thread.sleep(1000);
    }
  }

  private void runFallbackBeforeStartup() throws Exception {
    runShellCmd(induceFallbackCmd);
    final Deadline fallbackDeadline = Deadline.after(
        fallbackDeadlineSeconds, TimeUnit.SECONDS);
    initStub();
    waitForFallbackAndDoRpcs(fallbackDeadline);
  }

  private void runFallbackAfterStartup() throws Exception {
    initStub();
    assertEquals(
        GrpclbRouteType.GRPCLB_ROUTE_TYPE_BACKEND,
        doRpcAndGetPath(blockingStub, Deadline.after(20, TimeUnit.SECONDS)));
    runShellCmd(induceFallbackCmd);
    final Deadline fallbackDeadline = Deadline.after(
        fallbackDeadlineSeconds, TimeUnit.SECONDS);
    waitForFallbackAndDoRpcs(fallbackDeadline);
  }

  // The purpose of this warmup method is to get potentially expensive one-per-process
  // initialization out of the way, so that we can use aggressive timeouts in the actual
  // test cases. Note that the warmup phase is done using a separate channel from the
  // actual test cases, so that we don't affect the states of LB policies in the channel
  // of the actual test case.
  private void warmup() throws Exception {
    logger.info("Begin warmup, performing " + numWarmupRpcs + " RPCs on the warmup channel");
    ManagedChannel channel = createChannel();
    TestServiceGrpc.TestServiceBlockingStub stub = TestServiceGrpc.newBlockingStub(channel);
    for (int i = 0; i < numWarmupRpcs; i++) {
      doRpcAndGetPath(stub, Deadline.after(1, TimeUnit.SECONDS));
    }
    try {
      channel.shutdownNow();
      channel.awaitTermination(1, TimeUnit.SECONDS);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private void run() throws Exception {
    warmup();
    logger.info("Begin test case: " + testCase);
    if (testCase.equals("fallback_before_startup")) {
      runFallbackBeforeStartup();
    } else if (testCase.equals("fallback_after_startup")) {
      runFallbackAfterStartup();
    } else {
      throw new RuntimeException("invalid testcase: " + testCase);
    }
    logger.info("Test case: " + testCase + " done!");
  }
}
