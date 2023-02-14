/*
 * Copyright 2023 The gRPC Authors
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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertTrue;

import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannelBuilder;
import io.grpc.alts.ComputeEngineChannelCredentials;
import io.grpc.netty.NettyChannelBuilder;
import java.util.ArrayList;
import java.util.logging.Logger;

/**
 * Test client that can be used to verify that XDS federation works. A list of
 * server URIs (which can each be load balanced by different XDS servers), can
 * be configured via flags. A separate thread is created for each of these clients
 * and the configured test (either rpc_soak or channel_soak) is ran for each client
 * on each thread.
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
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              @SuppressWarnings("CatchAndPrintStackTrace")
              public void run() {
                System.out.println("Shutting down");
                try {
                  client.tearDown();
                } catch (RuntimeException e) {
                  e.printStackTrace();
                }
              }
            });
    client.setUp();
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
  private int soakMinTimeMsBetweenRpcs = 0;
  private String testCase = "rpc_soak";
  private final ArrayList<InnerClient> clients = new ArrayList<>();

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
      if (key.equals("help")) {
        usage = true;
        break;
      }
      if (parts.length != 2) {
        System.err.println("All arguments must be of the form --arg=value");
        usage = true;
        break;
      }
      String value = parts[1];
      switch (key) {
        case "server_uris":
          serverUris = value;
          break;
        case "credentials_types":
          credentialsTypes = value;
          break;
        case "test_case":
          testCase = value;
          break;
        case "soak_iterations":
          soakIterations = Integer.parseInt(value);
          break;
        case "soak_max_failures":
          soakMaxFailures = Integer.parseInt(value);
          break;
        case "soak_per_iteration_max_acceptable_latency_ms":
          soakPerIterationMaxAcceptableLatencyMs = Integer.parseInt(value);
          break;
        case "soak_overall_timeout_seconds":
          soakOverallTimeoutSeconds = Integer.parseInt(value);
          break;
        case "soak_min_time_ms_between_rpcs":
          soakMinTimeMsBetweenRpcs = Integer.parseInt(value);
          break;
        default:
          System.err.println("Unknown argument: " + key);
          usage = true;
          break;
      }
    }
    if (usage) {
      XdsFederationTestClient c = new XdsFederationTestClient();
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
          + "\n  --soak_per_iteration_max_acceptable_latency_ms"
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
          + c.soakMinTimeMsBetweenRpcs
          + "\n  --test_case=TEST_CASE                 Test case to run. Valid options are:"
          + "\n      rpc_soak: sends --soak_iterations large_unary RPCs"
          + "\n      channel_soak: sends --soak_iterations RPCs, rebuilding the channel "
          + "each time."
          + "\n      Default: " + c.testCase
      );
      System.exit(1);
    }
  }

  void setUp() {
    String[] uris = serverUris.split(",", -1);
    String[] creds = credentialsTypes.split(",", -1);
    if (uris.length == 0) {
      throw new IllegalArgumentException("--server_uris is empty");
    }
    if (uris.length != creds.length) {
      throw new IllegalArgumentException("Number of entries in --server_uris "
          + "does not match number of entries in --credentials_types");
    }
    for (int i = 0; i < uris.length; i++) {
      clients.add(new InnerClient(creds[i], uris[i]));
    }
    for (InnerClient c : clients) {
      c.setUp();
    }
  }

  private synchronized void tearDown() {
    for (InnerClient c : clients) {
      c.tearDown();
    }
  }

  /**
   * Wraps a single client stub configuration and executes a
   * soak test case with that configuration.
   */
  class InnerClient extends AbstractInteropTest {
    private final String credentialsType;
    private final String serverUri;
    private boolean runSucceeded = false;

    public InnerClient(String credentialsType, String serverUri) {
      this.credentialsType = credentialsType;
      this.serverUri = serverUri;
    }

    /**
     * Indicates whether run succeeded or not. This must only be called
     * after run() has finished.
     */
    public boolean runSucceeded() {
      return runSucceeded;
    }

    /**
     * Run the intended soak test.
     */
    public void run() {
      boolean resetChannelPerIteration;
      switch (testCase) {
        case "rpc_soak":
          resetChannelPerIteration = false;
          break;
        case "channel_soak":
          resetChannelPerIteration = true;
          break;
        default:
          throw new RuntimeException("invalid testcase: " + testCase);
      }
      try {
        performSoakTest(
            serverUri,
            resetChannelPerIteration,
            soakIterations,
            soakMaxFailures,
            soakPerIterationMaxAcceptableLatencyMs,
            soakMinTimeMsBetweenRpcs,
            soakOverallTimeoutSeconds);
        logger.info("Test case: " + testCase + " done for server: " + serverUri);
        runSucceeded = true;
      } catch (Exception e) {
        logger.info("Test case: " + testCase + " failed for server: " + serverUri);
        throw new RuntimeException(e);
      }
    }

    @Override
    protected ManagedChannelBuilder<?> createChannelBuilder() {
      ChannelCredentials channelCredentials;
      switch (credentialsType) {
        case "compute_engine_channel_creds":
          channelCredentials = ComputeEngineChannelCredentials.create();
          break;
        case "INSECURE_CREDENTIALS":
          channelCredentials = InsecureChannelCredentials.create();
          break;
        default:
          throw new IllegalArgumentException("Unknown custom credentials: " + credentialsType);
      }
      return NettyChannelBuilder.forTarget(serverUri, channelCredentials)
          .keepAliveTime(3600, SECONDS)
          .keepAliveTimeout(20, SECONDS);
    }
  }

  private void run() throws Exception {
    logger.info("Begin test case: " + testCase);
    ArrayList<Thread> threads = new ArrayList<>();
    for (InnerClient c : clients) {
      Thread t = new Thread(c::run);
      t.start();
      threads.add(t);
    }
    for (Thread t : threads) {
      t.join();
    }
    for (InnerClient c : clients) {
      assertTrue(c.runSucceeded());
    }
    logger.info("Test case: " + testCase + " done for all clients!");
  }
}
