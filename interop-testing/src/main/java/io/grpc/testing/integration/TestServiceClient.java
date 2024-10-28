/*
 * Copyright 2014 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.OAuth2Credentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Files;
import com.google.protobuf.ByteString;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerBuilder;
import io.grpc.TlsChannelCredentials;
import io.grpc.alts.AltsChannelCredentials;
import io.grpc.alts.ComputeEngineChannelCredentials;
import io.grpc.alts.GoogleDefaultChannelCredentials;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.JsonParser;
import io.grpc.netty.InsecureFromHttp1ChannelCredentials;
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.okhttp.InternalOkHttpChannelBuilder;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.TlsTesting;
import io.grpc.testing.integration.Messages.Payload;
import io.grpc.testing.integration.Messages.ResponseParameters;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import io.grpc.testing.integration.Messages.StreamingOutputCallRequest;
import io.grpc.testing.integration.Messages.StreamingOutputCallResponse;
import io.grpc.testing.integration.Messages.TestOrcaReport;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Application that starts a client for the {@link TestServiceGrpc.TestServiceImplBase} and runs
 * through a series of tests.
 */
public class TestServiceClient {

  private static final Charset UTF_8 = Charset.forName("UTF-8");

  static final CallOptions.Key<AtomicReference<TestOrcaReport>>
      ORCA_RPC_REPORT_KEY = CallOptions.Key.create("orca-rpc-report");
  static final CallOptions.Key<AtomicReference<TestOrcaReport>>
      ORCA_OOB_REPORT_KEY = CallOptions.Key.create("orca-oob-report");

  /**
   * The main application allowing this client to be launched from the command line.
   */
  public static void main(String[] args) throws Exception {
    final TestServiceClient client = new TestServiceClient();
    client.parseArgs(args);
    customBackendMetricsLoadBalancerProvider = new CustomBackendMetricsLoadBalancerProvider();
    LoadBalancerRegistry.getDefaultRegistry().register(customBackendMetricsLoadBalancerProvider);
    client.setUp();

    try {
      client.run();
    } finally {
      client.tearDown();
    }
  }

  private String serverHost = "localhost";
  private String serverHostOverride;
  private int serverPort = 8080;
  private String testCase = "empty_unary";
  private int numTimes = 1;
  private boolean useTls = true;
  private boolean useAlts = false;
  private boolean useH2cUpgrade = false;
  private String customCredentialsType;
  private boolean useTestCa;
  private boolean useOkHttp;
  private String defaultServiceAccount;
  private String serviceAccountKeyFile;
  private String oauthScope;
  private int localHandshakerPort = -1;
  private Map<String, ?> serviceConfig = null;
  private int soakIterations = 10;
  private int soakMaxFailures = 0;
  private int soakPerIterationMaxAcceptableLatencyMs = 1000;
  private int soakMinTimeMsBetweenRpcs = 0;
  private int soakOverallTimeoutSeconds =
      soakIterations * soakPerIterationMaxAcceptableLatencyMs / 1000;
  private int soakRequestSize = 271828;
  private int soakResponseSize = 314159;
  private String additionalMetadata = "";
  private static LoadBalancerProvider customBackendMetricsLoadBalancerProvider;

  private Tester tester = new Tester();

  @VisibleForTesting
  void parseArgs(String[] args) throws Exception {
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
      if ("server_host".equals(key)) {
        serverHost = value;
      } else if ("server_host_override".equals(key)) {
        serverHostOverride = value;
      } else if ("server_port".equals(key)) {
        serverPort = Integer.parseInt(value);
      } else if ("test_case".equals(key)) {
        testCase = value;
      } else if ("num_times".equals(key)) {
        numTimes = Integer.parseInt(value);
      } else if ("use_tls".equals(key)) {
        useTls = Boolean.parseBoolean(value);
      } else if ("use_upgrade".equals(key)) {
        useH2cUpgrade = Boolean.parseBoolean(value);
      } else if ("use_alts".equals(key)) {
        useAlts = Boolean.parseBoolean(value);
      } else if ("custom_credentials_type".equals(key)) {
        customCredentialsType = value;
      } else if ("use_test_ca".equals(key)) {
        useTestCa = Boolean.parseBoolean(value);
      } else if ("use_okhttp".equals(key)) {
        useOkHttp = Boolean.parseBoolean(value);
      } else if ("grpc_version".equals(key)) {
        if (!"2".equals(value)) {
          System.err.println("Only grpc version 2 is supported");
          usage = true;
          break;
        }
      } else if ("default_service_account".equals(key)) {
        defaultServiceAccount = value;
      } else if ("service_account_key_file".equals(key)) {
        serviceAccountKeyFile = value;
      } else if ("oauth_scope".equals(key)) {
        oauthScope = value;
      } else if ("local_handshaker_port".equals(key)) {
        localHandshakerPort = Integer.parseInt(value);
      } else if ("service_config_json".equals(key)) {
        @SuppressWarnings("unchecked")
        Map<String, ?> map = (Map<String, ?>) JsonParser.parse(value);
        serviceConfig = map;
      } else if ("soak_iterations".equals(key)) {
        soakIterations = Integer.parseInt(value);
      } else if ("soak_max_failures".equals(key)) {
        soakMaxFailures = Integer.parseInt(value);
      } else if ("soak_per_iteration_max_acceptable_latency_ms".equals(key)) {
        soakPerIterationMaxAcceptableLatencyMs = Integer.parseInt(value);
      } else if ("soak_min_time_ms_between_rpcs".equals(key)) {
        soakMinTimeMsBetweenRpcs = Integer.parseInt(value);
      } else if ("soak_overall_timeout_seconds".equals(key)) {
        soakOverallTimeoutSeconds = Integer.parseInt(value);
      } else if ("soak_request_size".equals(key)) {
        soakRequestSize = Integer.parseInt(value);
      } else if ("soak_response_size".equals(key)) {
        soakResponseSize = Integer.parseInt(value);
      } else if ("additional_metadata".equals(key)) {
        additionalMetadata = value;
      } else {
        System.err.println("Unknown argument: " + key);
        usage = true;
        break;
      }
    }
    if (useAlts || useH2cUpgrade) {
      useTls = false;
    }
    if (usage) {
      TestServiceClient c = new TestServiceClient();
      System.out.println(
          "Usage: [ARGS...]"
          + "\n"
          + "\n  --server_host=HOST          Server to connect to. Default " + c.serverHost
          + "\n  --server_host_override=HOST Claimed identification expected of server."
          + "\n                              Defaults to server host"
          + "\n  --server_port=PORT          Port to connect to. Default " + c.serverPort
          + "\n  --test_case=TESTCASE        Test case to run. Default " + c.testCase
          + "\n    Valid options:"
          + validTestCasesHelpText()
          + "\n  --num_times=INT             Number of times to run the test case. Default: "
          + c.numTimes
          + "\n  --use_tls=true|false        Whether to use TLS. Default " + c.useTls
          + "\n  --use_alts=true|false       Whether to use ALTS. Enable ALTS will disable TLS."
          + "\n                              Default " + c.useAlts
          + "\n  --local_handshaker_port=PORT"
          + "\n                              Use local ALTS handshaker service on the specified "
          + "\n                              port for testing. Only effective when --use_alts=true."
          + "\n  --use_upgrade=true|false    Whether to use the h2c Upgrade mechanism."
          + "\n                              Enabling h2c Upgrade will disable TLS."
          + "\n                              Default " + c.useH2cUpgrade
          + "\n  --custom_credentials_type   Custom credentials type to use. Default "
            + c.customCredentialsType
          + "\n  --use_test_ca=true|false    Whether to trust our fake CA. Requires --use_tls=true "
          + "\n                              to have effect. Default " + c.useTestCa
          + "\n  --use_okhttp=true|false     Whether to use OkHttp instead of Netty. Default "
            + c.useOkHttp
          + "\n  --default_service_account   Email of GCE default service account. Default "
            + c.defaultServiceAccount
          + "\n  --service_account_key_file  Path to service account json key file."
            + c.serviceAccountKeyFile
          + "\n  --oauth_scope               Scope for OAuth tokens. Default " + c.oauthScope
          + "\n --service_config_json=SERVICE_CONFIG_JSON"
          + "\n                              Disables service config lookups and sets the provided "
          + "\n                              string as the default service config."
          + "\n --soak_iterations            The number of iterations to use for the two soak "
          + "\n                              tests: rpc_soak and channel_soak. Default "
            + c.soakIterations
          + "\n --soak_max_failures          The number of iterations in soak tests that are "
          + "\n                              allowed to fail (either due to non-OK status code or "
          + "\n                              exceeding the per-iteration max acceptable latency). "
          + "\n                              Default " + c.soakMaxFailures
          + "\n --soak_per_iteration_max_acceptable_latency_ms "
          + "\n                              The number of milliseconds a single iteration in the "
          + "\n                              two soak tests (rpc_soak and channel_soak) should "
          + "\n                              take. Default "
            + c.soakPerIterationMaxAcceptableLatencyMs
          + "\n --soak_min_time_ms_between_rpcs "
          + "\n                              The minimum time in milliseconds between consecutive "
          + "\n                              RPCs in a soak test (rpc_soak or channel_soak), "
          + "\n                              useful for limiting QPS. Default: "
          + c.soakMinTimeMsBetweenRpcs
          + "\n --soak_overall_timeout_seconds "
          + "\n                              The overall number of seconds after which a soak test "
          + "\n                              should stop and fail, if the desired number of "
          + "\n                              iterations have not yet completed. Default "
            + c.soakOverallTimeoutSeconds
          + "\n --soak_request_size "
          + "\n                              The request size in a soak RPC. Default "
            + c.soakRequestSize
          + "\n --soak_response_size "
          + "\n                              The response size in a soak RPC. Default "
            + c.soakResponseSize
          + "\n --additional_metadata "
          + "\n                              Additional metadata to send in each request, as a "
          + "\n                              semicolon-separated list of key:value pairs. Default "
            + c.additionalMetadata
      );
      System.exit(1);
    }
  }

  @VisibleForTesting
  void setUp() {
    tester.setUp();
  }

  private synchronized void tearDown() {
    try {
      tester.tearDown();
      if (customBackendMetricsLoadBalancerProvider != null) {
        LoadBalancerRegistry.getDefaultRegistry()
            .deregister(customBackendMetricsLoadBalancerProvider);
      }
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private void run() {
    System.out.println("Running test " + testCase);
    try {
      for (int i = 0; i < numTimes; i++) {
        runTest(TestCases.fromString(testCase));
      }
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
    System.out.println("Test completed.");
  }

  private void runTest(TestCases testCase) throws Exception {
    switch (testCase) {
      case EMPTY_UNARY:
        tester.emptyUnary();
        break;

      case CACHEABLE_UNARY: {
        tester.cacheableUnary();
        break;
      }

      case LARGE_UNARY:
        tester.largeUnary();
        break;

      case CLIENT_COMPRESSED_UNARY:
        tester.clientCompressedUnary(true);
        break;

      case CLIENT_COMPRESSED_UNARY_NOPROBE:
        tester.clientCompressedUnary(false);
        break;

      case SERVER_COMPRESSED_UNARY:
        tester.serverCompressedUnary();
        break;

      case CLIENT_STREAMING:
        tester.clientStreaming();
        break;

      case CLIENT_COMPRESSED_STREAMING:
        tester.clientCompressedStreaming(true);
        break;

      case CLIENT_COMPRESSED_STREAMING_NOPROBE:
        tester.clientCompressedStreaming(false);
        break;

      case SERVER_STREAMING:
        tester.serverStreaming();
        break;

      case SERVER_COMPRESSED_STREAMING:
        tester.serverCompressedStreaming();
        break;

      case PING_PONG:
        tester.pingPong();
        break;

      case EMPTY_STREAM:
        tester.emptyStream();
        break;

      case COMPUTE_ENGINE_CREDS:
        tester.computeEngineCreds(defaultServiceAccount, oauthScope);
        break;

      case COMPUTE_ENGINE_CHANNEL_CREDENTIALS: {
        ManagedChannelBuilder<?> builder;
        if (serverPort == 0) {
          builder = Grpc.newChannelBuilder(serverHost, ComputeEngineChannelCredentials.create());
        } else {
          builder =
              Grpc.newChannelBuilderForAddress(
                  serverHost, serverPort, ComputeEngineChannelCredentials.create());
        }
        if (serviceConfig != null) {
          builder.disableServiceConfigLookUp();
          builder.defaultServiceConfig(serviceConfig);
        }
        ManagedChannel channel = builder.build();
        try {
          TestServiceGrpc.TestServiceBlockingStub computeEngineStub =
              TestServiceGrpc.newBlockingStub(channel);
          tester.computeEngineChannelCredentials(defaultServiceAccount, computeEngineStub);
        } finally {
          channel.shutdownNow();
          channel.awaitTermination(5, TimeUnit.SECONDS);
        }
        break;
      }

      case SERVICE_ACCOUNT_CREDS: {
        String jsonKey = Files.asCharSource(new File(serviceAccountKeyFile), UTF_8).read();
        FileInputStream credentialsStream = new FileInputStream(new File(serviceAccountKeyFile));
        tester.serviceAccountCreds(jsonKey, credentialsStream, oauthScope);
        break;
      }

      case JWT_TOKEN_CREDS: {
        FileInputStream credentialsStream = new FileInputStream(new File(serviceAccountKeyFile));
        tester.jwtTokenCreds(credentialsStream);
        break;
      }

      case OAUTH2_AUTH_TOKEN: {
        String jsonKey = Files.asCharSource(new File(serviceAccountKeyFile), UTF_8).read();
        FileInputStream credentialsStream = new FileInputStream(new File(serviceAccountKeyFile));
        tester.oauth2AuthToken(jsonKey, credentialsStream, oauthScope);
        break;
      }

      case PER_RPC_CREDS: {
        String jsonKey = Files.asCharSource(new File(serviceAccountKeyFile), UTF_8).read();
        FileInputStream credentialsStream = new FileInputStream(new File(serviceAccountKeyFile));
        tester.perRpcCreds(jsonKey, credentialsStream, oauthScope);
        break;
      }

      case GOOGLE_DEFAULT_CREDENTIALS: {
        ManagedChannelBuilder<?> builder;
        if (serverPort == 0) {
          builder = Grpc.newChannelBuilder(serverHost, GoogleDefaultChannelCredentials.create());
        } else {
          builder =
              Grpc.newChannelBuilderForAddress(
                  serverHost, serverPort, GoogleDefaultChannelCredentials.create());
        }
        if (serviceConfig != null) {
          builder.disableServiceConfigLookUp();
          builder.defaultServiceConfig(serviceConfig);
        }
        ManagedChannel channel = builder.build();
        try {
          TestServiceGrpc.TestServiceBlockingStub googleDefaultStub =
              TestServiceGrpc.newBlockingStub(channel);
          tester.googleDefaultCredentials(defaultServiceAccount, googleDefaultStub);
        } finally {
          channel.shutdownNow();
        }
        break;
      }

      case CUSTOM_METADATA: {
        tester.customMetadata();
        break;
      }

      case STATUS_CODE_AND_MESSAGE: {
        tester.statusCodeAndMessage();
        break;
      }

      case SPECIAL_STATUS_MESSAGE:
        tester.specialStatusMessage();
        break;

      case UNIMPLEMENTED_METHOD: {
        tester.unimplementedMethod();
        break;
      }

      case UNIMPLEMENTED_SERVICE: {
        tester.unimplementedService();
        break;
      }

      case CANCEL_AFTER_BEGIN: {
        tester.cancelAfterBegin();
        break;
      }

      case CANCEL_AFTER_FIRST_RESPONSE: {
        tester.cancelAfterFirstResponse();
        break;
      }

      case TIMEOUT_ON_SLEEPING_SERVER: {
        tester.timeoutOnSleepingServer();
        break;
      }

      case VERY_LARGE_REQUEST: {
        tester.veryLargeRequest();
        break;
      }

      case PICK_FIRST_UNARY: {
        tester.pickFirstUnary();
        break;
      }

      case RPC_SOAK: {
        tester.performSoakTest(
            serverHost,
            false /* resetChannelPerIteration */,
            false /* enableConcurrency */,
            soakIterations,
            soakMaxFailures,
            soakPerIterationMaxAcceptableLatencyMs,
            soakMinTimeMsBetweenRpcs,
            soakOverallTimeoutSeconds,
            soakRequestSize,
            soakResponseSize);
        break;
      }

      case RPC_SOAK_CONCURRENT: {
        tester.performSoakTest(
            serverHost,
            false /* resetChannelPerIteration */,
            true /* enableConcurrency */,
            soakIterations,
            soakMaxFailures,
            soakPerIterationMaxAcceptableLatencyMs,
            soakMinTimeMsBetweenRpcs,
            soakOverallTimeoutSeconds,
            soakRequestSize,
            soakResponseSize);
        break;
      }

      case CHANNEL_SOAK: {
        tester.performSoakTest(
            serverHost,
            true /* resetChannelPerIteration */,
            false /* enableConcurrency */,
            soakIterations,
            soakMaxFailures,
            soakPerIterationMaxAcceptableLatencyMs,
            soakMinTimeMsBetweenRpcs,
            soakOverallTimeoutSeconds,
            soakRequestSize,
            soakResponseSize);
        break;
      }

      case CHANNEL_SOAK_CONCURRENT: {
        tester.performSoakTest(
            serverHost,
            true /* resetChannelPerIteration */,
            true /* enableConcurrency */,
            soakIterations,
            soakMaxFailures,
            soakPerIterationMaxAcceptableLatencyMs,
            soakMinTimeMsBetweenRpcs,
            soakOverallTimeoutSeconds,
            soakRequestSize,
            soakResponseSize);
        break;
      }

      case ORCA_PER_RPC: {
        tester.testOrcaPerRpc();
        break;
      }

      case ORCA_OOB: {
        tester.testOrcaOob();
        break;
      }

      default:
        throw new IllegalArgumentException("Unknown test case: " + testCase);
    }
  }

  /* Parses input string as a semi-colon-separated list of colon-separated key/value pairs.
   * Allow any character but semicolons in values.
   * If the string is empty, return null.
   * Otherwise, return a client interceptor which inserts the provided metadata.
   */
  @Nullable
  private ClientInterceptor maybeCreateAdditionalMetadataInterceptor(
      String additionalMd)
      throws IllegalArgumentException {
    if (additionalMd.length() == 0) {
      return null;
    }
    Metadata metadata = new Metadata();
    String[] pairs = additionalMd.split(";", -1);
    for (String pair : pairs) {
      String[] parts = pair.split(":", 2);
      if (parts.length != 2) {
        throw new IllegalArgumentException(
            "error parsing --additional_metadata string, expected k:v pairs separated by ;");
      }
      Metadata.Key<String> key = Metadata.Key.of(parts[0], Metadata.ASCII_STRING_MARSHALLER);
      metadata.put(key, parts[1]);
    }
    return MetadataUtils.newAttachHeadersInterceptor(metadata);
  }

  private class Tester extends AbstractInteropTest {
    @Override
    protected ManagedChannelBuilder<?> createChannelBuilder() {
      boolean useGeneric = false;
      ChannelCredentials channelCredentials;
      if (customCredentialsType != null) {
        useGeneric = true; // Retain old behavior; avoids erroring if incompatible
        if (customCredentialsType.equals("google_default_credentials")) {
          channelCredentials = GoogleDefaultChannelCredentials.create();
        } else if (customCredentialsType.equals("compute_engine_channel_creds")) {
          channelCredentials = ComputeEngineChannelCredentials.create();
        } else {
          throw new IllegalArgumentException(
              "Unknown custom credentials: " + customCredentialsType);
        }

      } else if (useAlts) {
        useGeneric = true; // Retain old behavior; avoids erroring if incompatible
        if (localHandshakerPort > -1) {
          channelCredentials = AltsChannelCredentials.newBuilder()
              .enableUntrustedAltsForTesting()
              .setHandshakerAddressForTesting("localhost:" + localHandshakerPort).build();
        } else {
          channelCredentials = AltsChannelCredentials.create();
        }

      } else if (useTls) {
        if (!useTestCa) {
          channelCredentials = TlsChannelCredentials.create();
        } else {
          try {
            channelCredentials = TlsChannelCredentials.newBuilder()
                .trustManager(TlsTesting.loadCert("ca.pem"))
                .build();
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        }

      } else {
        if (useH2cUpgrade) {
          if (useOkHttp) {
            throw new IllegalArgumentException("OkHttp does not support HTTP/1 upgrade");
          } else {
            channelCredentials = InsecureFromHttp1ChannelCredentials.create();
          }
        } else {
          channelCredentials = InsecureChannelCredentials.create();
        }
      }
      ClientInterceptor addMdInterceptor = maybeCreateAdditionalMetadataInterceptor(
          additionalMetadata);
      if (useGeneric) {
        ManagedChannelBuilder<?> channelBuilder;
        if (serverPort == 0) {
          channelBuilder = Grpc.newChannelBuilder(serverHost, channelCredentials);
        } else {
          channelBuilder =
              Grpc.newChannelBuilderForAddress(serverHost, serverPort, channelCredentials);
        }
        if (serverHostOverride != null) {
          channelBuilder.overrideAuthority(serverHostOverride);
        }
        if (serviceConfig != null) {
          channelBuilder.disableServiceConfigLookUp();
          channelBuilder.defaultServiceConfig(serviceConfig);
        }
        if (addMdInterceptor != null) {
          channelBuilder.intercept(addMdInterceptor);
        }
        return channelBuilder;
      }
      if (!useOkHttp) {
        NettyChannelBuilder nettyBuilder;
        if (serverPort == 0) {
          nettyBuilder = NettyChannelBuilder.forTarget(serverHost, channelCredentials);
        } else {
          nettyBuilder = NettyChannelBuilder.forAddress(serverHost, serverPort, channelCredentials);
        }
        nettyBuilder.flowControlWindow(AbstractInteropTest.TEST_FLOW_CONTROL_WINDOW);
        if (serverHostOverride != null) {
          nettyBuilder.overrideAuthority(serverHostOverride);
        }
        // Disable the default census stats interceptor, use testing interceptor instead.
        InternalNettyChannelBuilder.setStatsEnabled(nettyBuilder, false);
        if (serviceConfig != null) {
          nettyBuilder.disableServiceConfigLookUp();
          nettyBuilder.defaultServiceConfig(serviceConfig);
        }
        if (addMdInterceptor != null) {
          nettyBuilder.intercept(addMdInterceptor);
        }
        return nettyBuilder.intercept(createCensusStatsClientInterceptor());
      }

      OkHttpChannelBuilder okBuilder;
      if (serverPort == 0) {
        okBuilder = OkHttpChannelBuilder.forTarget(serverHost, channelCredentials);
      } else {
        okBuilder = OkHttpChannelBuilder.forAddress(serverHost, serverPort, channelCredentials);
      }
      if (serverHostOverride != null) {
        // Force the hostname to match the cert the server uses.
        okBuilder.overrideAuthority(
            GrpcUtil.authorityFromHostAndPort(serverHostOverride, serverPort));
      }
      // Disable the default census stats interceptor, use testing interceptor instead.
      InternalOkHttpChannelBuilder.setStatsEnabled(okBuilder, false);
      if (serviceConfig != null) {
        okBuilder.disableServiceConfigLookUp();
        okBuilder.defaultServiceConfig(serviceConfig);
      }
      if (addMdInterceptor != null) {
        okBuilder.intercept(addMdInterceptor);
      }
      return okBuilder.intercept(createCensusStatsClientInterceptor());
    }

    /**
     * Assuming "pick_first" policy is used, tests that all requests are sent to the same server.
     */
    public void pickFirstUnary() throws Exception {
      SimpleRequest request = SimpleRequest.newBuilder()
          .setResponseSize(1)
          .setFillServerId(true)
          .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[1])))
          .build();

      SimpleResponse firstResponse = blockingStub.unaryCall(request);
      // Increase the chance of all servers are connected, in case the channel should be doing
      // round_robin instead.
      Thread.sleep(5000);
      for (int i = 0; i < 100; i++) {
        SimpleResponse response = blockingStub.unaryCall(request);
        assertThat(response.getServerId()).isEqualTo(firstResponse.getServerId());
      }
    }

    /**
     * Sends a cacheable unary rpc using GET. Requires that the server is behind a caching proxy.
     */
    public void cacheableUnary() {
      // THIS TEST IS BROKEN. Enabling safe just on the MethodDescriptor does nothing by itself.
      // This test would need to enable GET on the channel.
      // Set safe to true.
      MethodDescriptor<SimpleRequest, SimpleResponse> safeCacheableUnaryCallMethod =
          TestServiceGrpc.getCacheableUnaryCallMethod().toBuilder().setSafe(true).build();
      // Set fake user IP since some proxies (GFE) won't cache requests from localhost.
      Metadata.Key<String> userIpKey =
          Metadata.Key.of("x-user-ip", Metadata.ASCII_STRING_MARSHALLER);
      Metadata metadata = new Metadata();
      metadata.put(userIpKey, "1.2.3.4");
      Channel channelWithUserIpKey = ClientInterceptors.intercept(
          channel, MetadataUtils.newAttachHeadersInterceptor(metadata));
      SimpleRequest requests1And2 =
          SimpleRequest.newBuilder()
              .setPayload(
                  Payload.newBuilder()
                      .setBody(ByteString.copyFromUtf8(String.valueOf(System.nanoTime()))))
              .build();
      SimpleRequest request3 =
          SimpleRequest.newBuilder()
              .setPayload(
                  Payload.newBuilder()
                      .setBody(ByteString.copyFromUtf8(String.valueOf(System.nanoTime()))))
              .build();

      SimpleResponse response1 =
          ClientCalls.blockingUnaryCall(
              channelWithUserIpKey, safeCacheableUnaryCallMethod, CallOptions.DEFAULT,
              requests1And2);
      SimpleResponse response2 =
          ClientCalls.blockingUnaryCall(
              channelWithUserIpKey, safeCacheableUnaryCallMethod, CallOptions.DEFAULT,
              requests1And2);
      SimpleResponse response3 =
          ClientCalls.blockingUnaryCall(
              channelWithUserIpKey, safeCacheableUnaryCallMethod, CallOptions.DEFAULT, request3);

      assertEquals(response1, response2);
      assertNotEquals(response1, response3);
      // THIS TEST IS BROKEN. See comment at start of method.
    }

    /** Sends a large unary rpc with service account credentials. */
    public void serviceAccountCreds(String jsonKey, InputStream credentialsStream, String authScope)
        throws Exception {
      // cast to ServiceAccountCredentials to double-check the right type of object was created.
      GoogleCredentials credentials =
          ServiceAccountCredentials.class.cast(GoogleCredentials.fromStream(credentialsStream));
      credentials = credentials.createScoped(Arrays.asList(authScope));
      TestServiceGrpc.TestServiceBlockingStub stub = blockingStub
          .withCallCredentials(MoreCallCredentials.from(credentials));
      final SimpleRequest request = SimpleRequest.newBuilder()
          .setFillUsername(true)
          .setFillOauthScope(true)
          .setResponseSize(314159)
          .setPayload(Payload.newBuilder()
              .setBody(ByteString.copyFrom(new byte[271828])))
          .build();

      final SimpleResponse response = stub.unaryCall(request);
      assertFalse(response.getUsername().isEmpty());
      assertTrue("Received username: " + response.getUsername(),
          jsonKey.contains(response.getUsername()));
      assertFalse(response.getOauthScope().isEmpty());
      assertTrue("Received oauth scope: " + response.getOauthScope(),
          authScope.contains(response.getOauthScope()));

      final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
          .setOauthScope(response.getOauthScope())
          .setUsername(response.getUsername())
          .setPayload(Payload.newBuilder()
              .setBody(ByteString.copyFrom(new byte[314159])))
          .build();
      assertResponse(goldenResponse, response);
    }

    /** Sends a large unary rpc with compute engine credentials. */
    public void computeEngineCreds(String serviceAccount, String oauthScope) throws Exception {
      ComputeEngineCredentials credentials = ComputeEngineCredentials.create();
      TestServiceGrpc.TestServiceBlockingStub stub = blockingStub
          .withCallCredentials(MoreCallCredentials.from(credentials));
      final SimpleRequest request = SimpleRequest.newBuilder()
          .setFillUsername(true)
          .setFillOauthScope(true)
          .setResponseSize(314159)
          .setPayload(Payload.newBuilder()
              .setBody(ByteString.copyFrom(new byte[271828])))
          .build();

      final SimpleResponse response = stub.unaryCall(request);
      assertEquals(serviceAccount, response.getUsername());
      assertFalse(response.getOauthScope().isEmpty());
      assertTrue("Received oauth scope: " + response.getOauthScope(),
          oauthScope.contains(response.getOauthScope()));

      final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
          .setOauthScope(response.getOauthScope())
          .setUsername(response.getUsername())
          .setPayload(Payload.newBuilder()
              .setBody(ByteString.copyFrom(new byte[314159])))
          .build();
      assertResponse(goldenResponse, response);
    }

    /** Sends an unary rpc with ComputeEngineChannelBuilder. */
    public void computeEngineChannelCredentials(
        String defaultServiceAccount,
        TestServiceGrpc.TestServiceBlockingStub computeEngineStub) throws Exception {
      final SimpleRequest request = SimpleRequest.newBuilder()
          .setFillUsername(true)
          .setResponseSize(314159)
          .setPayload(Payload.newBuilder()
          .setBody(ByteString.copyFrom(new byte[271828])))
          .build();
      final SimpleResponse response = computeEngineStub.unaryCall(request);
      assertEquals(defaultServiceAccount, response.getUsername());
      final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
          .setUsername(defaultServiceAccount)
          .setPayload(Payload.newBuilder()
          .setBody(ByteString.copyFrom(new byte[314159])))
          .build();
      assertResponse(goldenResponse, response);
    }

    /** Test JWT-based auth. */
    public void jwtTokenCreds(InputStream serviceAccountJson) throws Exception {
      final SimpleRequest request = SimpleRequest.newBuilder()
          .setResponseSize(314159)
          .setPayload(Payload.newBuilder()
              .setBody(ByteString.copyFrom(new byte[271828])))
          .setFillUsername(true)
          .build();

      ServiceAccountCredentials credentials = (ServiceAccountCredentials)
          GoogleCredentials.fromStream(serviceAccountJson);
      TestServiceGrpc.TestServiceBlockingStub stub = blockingStub
          .withCallCredentials(MoreCallCredentials.from(credentials));
      SimpleResponse response = stub.unaryCall(request);
      assertEquals(credentials.getClientEmail(), response.getUsername());
      assertEquals(314159, response.getPayload().getBody().size());
    }

    /** Sends a unary rpc with raw oauth2 access token credentials. */
    public void oauth2AuthToken(String jsonKey, InputStream credentialsStream, String authScope)
        throws Exception {
      GoogleCredentials utilCredentials =
          GoogleCredentials.fromStream(credentialsStream);
      utilCredentials = utilCredentials.createScoped(Arrays.asList(authScope));
      AccessToken accessToken = utilCredentials.refreshAccessToken();

      OAuth2Credentials credentials = OAuth2Credentials.create(accessToken);

      TestServiceGrpc.TestServiceBlockingStub stub = blockingStub
          .withCallCredentials(MoreCallCredentials.from(credentials));
      final SimpleRequest request = SimpleRequest.newBuilder()
          .setFillUsername(true)
          .setFillOauthScope(true)
          .build();

      final SimpleResponse response = stub.unaryCall(request);
      assertFalse(response.getUsername().isEmpty());
      assertTrue("Received username: " + response.getUsername(),
          jsonKey.contains(response.getUsername()));
      assertFalse(response.getOauthScope().isEmpty());
      assertTrue("Received oauth scope: " + response.getOauthScope(),
          authScope.contains(response.getOauthScope()));
    }

    /** Sends a unary rpc with "per rpc" raw oauth2 access token credentials. */
    public void perRpcCreds(String jsonKey, InputStream credentialsStream, String oauthScope)
        throws Exception {
      // In gRpc Java, we don't have per Rpc credentials, user can use an intercepted stub only once
      // for that purpose.
      // So, this test is identical to oauth2_auth_token test.
      oauth2AuthToken(jsonKey, credentialsStream, oauthScope);
    }

    /** Sends an unary rpc with "google default credentials". */
    public void googleDefaultCredentials(
        String defaultServiceAccount,
        TestServiceGrpc.TestServiceBlockingStub googleDefaultStub) throws Exception {
      final SimpleRequest request = SimpleRequest.newBuilder()
          .setFillUsername(true)
          .setResponseSize(314159)
          .setPayload(Payload.newBuilder()
              .setBody(ByteString.copyFrom(new byte[271828])))
          .build();
      final SimpleResponse response = googleDefaultStub.unaryCall(request);
      assertEquals(defaultServiceAccount, response.getUsername());

      final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
          .setUsername(defaultServiceAccount)
          .setPayload(Payload.newBuilder()
              .setBody(ByteString.copyFrom(new byte[314159])))
          .build();
      assertResponse(goldenResponse, response);
    }

    /**
     *  Test backend metrics per query reporting: expect the test client LB policy to receive load
     *  reports.
     */
    public void testOrcaPerRpc() throws Exception {
      AtomicReference<TestOrcaReport> reportHolder = new AtomicReference<>();
      TestOrcaReport answer = TestOrcaReport.newBuilder()
          .setCpuUtilization(0.8210)
          .setMemoryUtilization(0.5847)
          .putRequestCost("cost", 3456.32)
          .putUtilization("util", 0.30499)
          .build();
      blockingStub.withOption(ORCA_RPC_REPORT_KEY, reportHolder).unaryCall(
          SimpleRequest.newBuilder().setOrcaPerQueryReport(answer).build());
      assertThat(reportHolder.get()).isEqualTo(answer);
    }

    /**
     * Test backend metrics OOB reporting: expect the test client LB policy to receive load reports.
     */
    public void testOrcaOob() throws Exception {
      AtomicReference<TestOrcaReport> reportHolder = new AtomicReference<>();
      final TestOrcaReport answer = TestOrcaReport.newBuilder()
          .setCpuUtilization(0.8210)
          .setMemoryUtilization(0.5847)
          .putUtilization("util", 0.30499)
          .build();
      final TestOrcaReport answer2 = TestOrcaReport.newBuilder()
          .setCpuUtilization(0.29309)
          .setMemoryUtilization(0.2)
          .putUtilization("util", 0.2039)
          .build();

      final int retryLimit = 5;
      BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
      final Object lastItem = new Object();
      StreamObserver<StreamingOutputCallRequest> streamObserver =
          asyncStub.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {

            @Override
            public void onNext(StreamingOutputCallResponse value) {
              queue.add(value);
            }

            @Override
            public void onError(Throwable t) {
              queue.add(t);
            }

            @Override
            public void onCompleted() {
              queue.add(lastItem);
            }
          });

      streamObserver.onNext(StreamingOutputCallRequest.newBuilder()
          .setOrcaOobReport(answer)
          .addResponseParameters(ResponseParameters.newBuilder().setSize(1).build()).build());
      assertThat(queue.take()).isInstanceOf(StreamingOutputCallResponse.class);
      int i = 0;
      for (; i < retryLimit; i++) {
        Thread.sleep(1000);
        blockingStub.withOption(ORCA_OOB_REPORT_KEY, reportHolder).emptyCall(EMPTY);
        if (answer.equals(reportHolder.get())) {
          break;
        }
      }
      assertThat(i).isLessThan(retryLimit);
      streamObserver.onNext(StreamingOutputCallRequest.newBuilder()
          .setOrcaOobReport(answer2)
          .addResponseParameters(ResponseParameters.newBuilder().setSize(1).build()).build());
      assertThat(queue.take()).isInstanceOf(StreamingOutputCallResponse.class);

      for (i = 0; i < retryLimit; i++) {
        Thread.sleep(1000);
        blockingStub.withOption(ORCA_OOB_REPORT_KEY, reportHolder).emptyCall(EMPTY);
        if (reportHolder.get().equals(answer2)) {
          break;
        }
      }
      assertThat(i).isLessThan(retryLimit);
      streamObserver.onCompleted();
      assertThat(queue.take()).isSameInstanceAs(lastItem);
    }

    @Override
    protected boolean metricsExpected() {
      // Exact message size doesn't match when testing with Go servers:
      // https://github.com/grpc/grpc-go/issues/1572
      // TODO(zhangkun83): remove this override once the said issue is fixed.
      return false;
    }

    @Override
    @Nullable
    protected ServerBuilder<?> getHandshakerServerBuilder() {
      if (localHandshakerPort > -1) {
        return Grpc.newServerBuilderForPort(localHandshakerPort,
            InsecureServerCredentials.create())
            .addService(new AltsHandshakerTestService());
      } else {
        return null;
      }
    }

    @Override
    protected int operationTimeoutMillis() {
      return 15000;
    }
  }

  private static String validTestCasesHelpText() {
    StringBuilder builder = new StringBuilder();
    for (TestCases testCase : TestCases.values()) {
      String strTestcase = testCase.toString();
      builder.append("\n      ")
          .append(strTestcase)
          .append(": ")
          .append(testCase.description());
    }
    return builder.toString();
  }
}
