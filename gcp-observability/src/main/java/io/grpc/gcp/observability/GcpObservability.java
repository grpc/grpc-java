/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.gcp.observability;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import io.grpc.ClientInterceptor;
import io.grpc.InternalGlobalInterceptors;
import io.grpc.ManagedChannelProvider.ProviderNotFoundException;
import io.grpc.ServerInterceptor;
import io.grpc.ServerStreamTracer;
import io.grpc.census.InternalCensusStatsAccessor;
import io.grpc.census.InternalCensusTracingAccessor;
import io.grpc.census.internal.ObservabilityCensusConstants;
import io.grpc.gcp.observability.interceptors.ConditionalClientInterceptor;
import io.grpc.gcp.observability.interceptors.ConfigFilterHelper;
import io.grpc.gcp.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.gcp.observability.interceptors.InternalLoggingServerInterceptor;
import io.grpc.gcp.observability.interceptors.LogHelper;
import io.grpc.gcp.observability.logging.GcpLogSink;
import io.grpc.gcp.observability.logging.Sink;
import io.grpc.gcp.observability.logging.TraceLoggingHelper;
import io.opencensus.common.Duration;
import io.opencensus.contrib.grpc.metrics.RpcViewConstants;
import io.opencensus.exporter.stats.stackdriver.StackdriverStatsConfiguration;
import io.opencensus.exporter.stats.stackdriver.StackdriverStatsExporter;
import io.opencensus.exporter.trace.stackdriver.StackdriverTraceConfiguration;
import io.opencensus.exporter.trace.stackdriver.StackdriverTraceExporter;
import io.opencensus.metrics.LabelKey;
import io.opencensus.metrics.LabelValue;
import io.opencensus.stats.Stats;
import io.opencensus.stats.ViewManager;
import io.opencensus.trace.AttributeValue;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.config.TraceConfig;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** The main class for gRPC Google Cloud Platform Observability features. */
public final class GcpObservability implements AutoCloseable {

  private static final Logger logger = Logger.getLogger(GcpObservability.class.getName());
  private static final int METRICS_EXPORT_INTERVAL = 30;

  static final String DEFAULT_METRIC_CUSTOM_TAG_KEY = "opencensus_task";
  @VisibleForTesting
  static final ImmutableSet<String> SERVICES_TO_EXCLUDE = ImmutableSet.of(
      "google.logging.v2.LoggingServiceV2", "google.monitoring.v3.MetricService",
      "google.devtools.cloudtrace.v2.TraceService");

  private static GcpObservability instance = null;
  private final Sink sink;
  private final ObservabilityConfig config;
  private final ArrayList<ClientInterceptor> clientInterceptors = new ArrayList<>();
  private final ArrayList<ServerInterceptor> serverInterceptors = new ArrayList<>();
  private final ArrayList<ServerStreamTracer.Factory> tracerFactories = new ArrayList<>();

  /**
   * Initialize grpc-observability.
   *
   * @throws ProviderNotFoundException if no underlying channel/server provider is available.
   */
  public static synchronized GcpObservability grpcInit() throws IOException {
    if (instance == null) {
      ObservabilityConfigImpl observabilityConfig = ObservabilityConfigImpl.getInstance();
      TraceLoggingHelper traceLoggingHelper = new TraceLoggingHelper(
          observabilityConfig.getProjectId());
      Sink sink = new GcpLogSink(observabilityConfig.getProjectId(), observabilityConfig,
          SERVICES_TO_EXCLUDE, traceLoggingHelper);
      LogHelper helper = new LogHelper(sink);
      ConfigFilterHelper configFilterHelper = ConfigFilterHelper.getInstance(observabilityConfig);
      instance = grpcInit(sink, observabilityConfig,
          new InternalLoggingChannelInterceptor.FactoryImpl(helper, configFilterHelper),
          new InternalLoggingServerInterceptor.FactoryImpl(helper, configFilterHelper));
      instance.registerStackDriverExporter(observabilityConfig.getProjectId(),
          observabilityConfig.getCustomTags());
    }
    return instance;
  }

  @VisibleForTesting
  static GcpObservability grpcInit(
      Sink sink,
      ObservabilityConfig config,
      InternalLoggingChannelInterceptor.Factory channelInterceptorFactory,
      InternalLoggingServerInterceptor.Factory serverInterceptorFactory)
      throws IOException {
    if (instance == null) {
      instance = new GcpObservability(sink, config);
      instance.setProducer(channelInterceptorFactory, serverInterceptorFactory);
    }
    return instance;
  }

  /** Un-initialize/shutdown grpc-observability. */
  @Override
  public void close() {
    synchronized (GcpObservability.class) {
      if (instance == null) {
        throw new IllegalStateException("GcpObservability already closed!");
      }
      sink.close();
      if (config.isEnableCloudMonitoring() || config.isEnableCloudTracing()) {
        try {
          // Sleeping before shutdown to ensure all metrics and traces are flushed
          Thread.sleep(
              TimeUnit.MILLISECONDS.convert(2 * METRICS_EXPORT_INTERVAL, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          logger.log(Level.SEVERE, "Caught exception during sleep", e);
        }
      }
      instance = null;
    }
  }

  // TODO(dnvindhya): Remove <channel/server>InterceptorFactory and replace with respective
  // interceptors
  private void setProducer(
      InternalLoggingChannelInterceptor.Factory channelInterceptorFactory,
      InternalLoggingServerInterceptor.Factory serverInterceptorFactory) {
    if (config.isEnableCloudLogging()) {
      clientInterceptors.add(channelInterceptorFactory.create());
      serverInterceptors.add(serverInterceptorFactory.create());
    }
    if (config.isEnableCloudMonitoring()) {
      clientInterceptors.add(getConditionalInterceptor(
          InternalCensusStatsAccessor.getClientInterceptor(true, true, false, true)));
      tracerFactories.add(
          InternalCensusStatsAccessor.getServerStreamTracerFactory(true, true, false));
    }
    if (config.isEnableCloudTracing()) {
      clientInterceptors.add(
          getConditionalInterceptor(InternalCensusTracingAccessor.getClientInterceptor()));
      tracerFactories.add(InternalCensusTracingAccessor.getServerStreamTracerFactory());
    }

    InternalGlobalInterceptors.setInterceptorsTracers(
        clientInterceptors, serverInterceptors, tracerFactories);
  }

  static ConditionalClientInterceptor getConditionalInterceptor(ClientInterceptor interceptor) {
    return new ConditionalClientInterceptor(interceptor,
        (m, c) -> !SERVICES_TO_EXCLUDE.contains(m.getServiceName()));
  }

  private static void registerObservabilityViews() {
    ViewManager viewManager = Stats.getViewManager();

    // client views
    viewManager.registerView(RpcViewConstants.GRPC_CLIENT_COMPLETED_RPC_VIEW);
    viewManager.registerView(RpcViewConstants.GRPC_CLIENT_STARTED_RPC_VIEW);
    viewManager.registerView(RpcViewConstants.GRPC_CLIENT_ROUNDTRIP_LATENCY_VIEW);
    viewManager.registerView(ObservabilityCensusConstants.GRPC_CLIENT_API_LATENCY_VIEW);
    viewManager.registerView(
        ObservabilityCensusConstants.GRPC_CLIENT_SENT_COMPRESSED_MESSAGE_BYTES_PER_RPC_VIEW);
    viewManager.registerView(
        ObservabilityCensusConstants.GRPC_CLIENT_RECEIVED_COMPRESSED_MESSAGE_BYTES_PER_RPC_VIEW);

    // server views
    viewManager.registerView(RpcViewConstants.GRPC_SERVER_COMPLETED_RPC_VIEW);
    viewManager.registerView(RpcViewConstants.GRPC_SERVER_STARTED_RPC_VIEW);
    viewManager.registerView(RpcViewConstants.GRPC_SERVER_SERVER_LATENCY_VIEW);
    viewManager.registerView(
        ObservabilityCensusConstants.GRPC_SERVER_SENT_COMPRESSED_MESSAGE_BYTES_PER_RPC_VIEW);
    viewManager.registerView(
        ObservabilityCensusConstants.GRPC_SERVER_RECEIVED_COMPRESSED_MESSAGE_BYTES_PER_RPC_VIEW);
  }

  @VisibleForTesting
  void registerStackDriverExporter(String projectId, Map<String, String> customTags)
      throws IOException {
    if (config.isEnableCloudMonitoring()) {
      registerObservabilityViews();
      StackdriverStatsConfiguration.Builder statsConfigurationBuilder =
          StackdriverStatsConfiguration.builder();
      if (projectId != null) {
        statsConfigurationBuilder.setProjectId(projectId);
      }
      Map<LabelKey, LabelValue> constantLabels = new HashMap<>();
      constantLabels.put(
          LabelKey.create(DEFAULT_METRIC_CUSTOM_TAG_KEY, DEFAULT_METRIC_CUSTOM_TAG_KEY),
          LabelValue.create(generateDefaultMetricTagValue()));
      if (customTags != null) {
        for (Map.Entry<String, String> mapEntry : customTags.entrySet()) {
          constantLabels.putIfAbsent(LabelKey.create(mapEntry.getKey(), mapEntry.getKey()),
              LabelValue.create(mapEntry.getValue()));
        }
      }
      statsConfigurationBuilder.setConstantLabels(constantLabels);
      statsConfigurationBuilder.setExportInterval(Duration.create(METRICS_EXPORT_INTERVAL, 0));
      StackdriverStatsExporter.createAndRegister(statsConfigurationBuilder.build());
    }

    if (config.isEnableCloudTracing()) {
      TraceConfig traceConfig = Tracing.getTraceConfig();
      traceConfig.updateActiveTraceParams(
          traceConfig.getActiveTraceParams().toBuilder().setSampler(config.getSampler()).build());
      StackdriverTraceConfiguration.Builder traceConfigurationBuilder =
          StackdriverTraceConfiguration.builder();
      if (projectId != null) {
        traceConfigurationBuilder.setProjectId(projectId);
      }
      if (customTags != null) {
        Map<String, AttributeValue> fixedAttributes = customTags.entrySet().stream()
            .collect(Collectors.toMap(e -> e.getKey(),
                e -> AttributeValue.stringAttributeValue(e.getValue())));
        traceConfigurationBuilder.setFixedAttributes(fixedAttributes);
      }
      StackdriverTraceExporter.createAndRegister(traceConfigurationBuilder.build());
    }
  }

  private static String generateDefaultMetricTagValue() {
    final String jvmName = ManagementFactory.getRuntimeMXBean().getName();
    if (jvmName.indexOf('@') < 1) {
      String hostname = "localhost";
      try {
        hostname = InetAddress.getLocalHost().getHostName();
      } catch (UnknownHostException e) {
        logger.log(Level.INFO, "Unable to get the hostname.", e);
      }
      return "java-" + new SecureRandom().nextInt() + "@" + hostname;
    }
    return "java-" + jvmName;
  }

  private GcpObservability(
      Sink sink,
      ObservabilityConfig config) {
    this.sink = checkNotNull(sink);
    this.config = checkNotNull(config);
  }
}
