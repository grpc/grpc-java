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
public final class XdsFederationTestClient {
  private static final Logger logger =
      Logger.getLogger(XdsFederationTestClient.class.getName());

  /**
   * Entry point.
   */
  public static void main(String[] args) throws Exception {
    final XdsFederationTestClient client = new XdsFederationTestClient();
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

  private String serverUris = "";
  private String credentialsTypes = "";
  private int soakIterations = 10;
  private int soakMaxFailures = 0;
  private int soakPerIterationMaxAcceptableLatencyMs = 1000;
  private int soakOverallTimeoutSeconds = 10;
  private int soakMinTimeMsBetweenRPCs = 0;
  private String testCase = "rpc_soak";

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
      if ("server_uris".equals(key)) {
        serverUris = value;
      } else if ("test_case".equals(key)) {
        testCase = value;
      } else if ("soak_iterations".equals(key)) {
        soakIterations = Integer.valueOf(value);
      } else if ("soak_max_failures".equals(key)) {
        soakMaxFailures = Integer.valueOf(value);
      } else if ("soak_per_iteration_max_acceptable_latency_ms".equals(key)) {
        soakPerIterationMaxAcceptableLatencyMs = Integer.valueOf(value);
      } else if ("soak_overall_timeout_seconds".equals(key)) {
        soakOverallTimeoutSeconds = Integer.valueOf(value);
      } else if ("soak_min_time_ms_between_rpcs".equals(key)) {
        soakMinTimeMsBetweenRPCs = Integer.valueOf(value);
      } else {
        System.err.println("Unknown argument: " + key);
        usage = true;
        break;
      }
    }
    if (usage) {
      XdsFederationTestClinet c = new XdsFederationTestClient();
      System.out.println(
          "Usage: [ARGS...]"
          + "\n"
          + "\n  --server_uris                         Comma separated list of server "
          + "URIs to make RPCs to. Default: "
          + c.serverUris
          + "\n  --credentials_types                   Comma-separated list of "
          + "\n                                        credentials, each entry is used "
          + "\n                                        for the server of the "
          + "\n                                        corresponding index in server_uris. "
          + "\n                                        Supported values: "
          + "compute_engine_channel_creds,INSECURE_CREDENTIALS. Default: "
          + c.credentialsTypes
          + "\n  --soak_iterations                     The number of iterations to use "
          + "\n                                        for the two tests: rpc_soak and "
          + "\n                                        channel_soak. Default: "
          + c.soakIterations
          + "\n  --soak_max_failures                   The number of iterations in soak "
          + "\n                                        tests that are allowed to fail "
          + "\n                                        (either due to non-OK status code "
          + "\n                                        or exceeding the per-iteration max "
          + "\n                                        acceptable latency). Default: "
          + c.soakMaxFailures
          + "\n  --soak_per_iteration_max_acceptable_latency_ms
          + "\n                                        The number of milliseconds a "
          + "\n                                        single iteration in the two soak "
          + "\n                                        tests (rpc_soak and channel_soak) "
          + "\n                                        should take. Default: "
          + c.soakPerIterationMaxAcceptableLatencyMs
          + "\n  --soak_overall_timeout_seconds        The overall number of seconds "
          + "\n                                        after which a soak test should "
          + "\n                                        stop and fail, if the desired "
          + "\n                                        number of iterations have not yet "
          + "\n                                        completed. Default: "
          + c.soakOverallTimeoutSeconds
          + "\n  --soak_min_time_ms_between_rpcs       The minimum time in milliseconds "
          + "\n                                        between consecutive RPCs in a soak "
          + "\n                                        test (rpc_soak or channel_soak), "
          + "\n                                        useful for limiting QPS. Default: "
          + c.soakMinTimeMsBetweenRPCs
          + "\n  --test_case=TEST_CASE                 Test case to run. Valid options are:"
          + "\n      rpc_soak: sends --soak_iterations large_unary RPCs"
          + "\n      channel_soak: sends --soak_iterations RPCs, rebuilding the channel "
          + "each time."
          + "\n      Default: " + c.testCase
      );
      System.exit(1);
    }
  }

  private void run() throws Exception {
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
