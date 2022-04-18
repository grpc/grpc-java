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

package io.grpc.xds.orca;

import static com.google.common.truth.Truth.assertThat;

import com.github.xds.data.orca.v3.OrcaLoadReport;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Context;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link OrcaMetricReportingServerInterceptor}.
 */
@RunWith(JUnit4.class)
public class OrcaMetricReportingServerInterceptorTest {

  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  private static final MethodDescriptor<SimpleRequest, SimpleResponse> SIMPLE_METHOD =
      SimpleServiceGrpc.getUnaryRpcMethod();

  private static final SimpleRequest REQUEST =
      SimpleRequest.newBuilder().setRequestMessage("Simple request").build();

  private final Map<String, Double> applicationUtilizationMetrics = new HashMap<>();
  private final Map<String, Double> applicationCostMetrics = new HashMap<>();
  private double cpuUtilizationMetrics = 0;
  private double memoryUtilizationMetrics = 0;

  private final AtomicReference<Metadata> trailersCapture = new AtomicReference<>();

  private Channel channelToUse;

  @Before
  public void setUp() throws Exception {
    SimpleServiceGrpc.SimpleServiceImplBase simpleServiceImpl =
        new SimpleServiceGrpc.SimpleServiceImplBase() {
          @Override
          public void unaryRpc(
              SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            for (Map.Entry<String, Double> entry : applicationUtilizationMetrics.entrySet()) {
              CallMetricRecorder.getCurrent().recordUtilizationMetric(entry.getKey(),
                  entry.getValue());
            }
            for (Map.Entry<String, Double> entry : applicationCostMetrics.entrySet()) {
              CallMetricRecorder.getCurrent().recordRequestCostMetric(entry.getKey(),
                  entry.getValue());
            }
            CallMetricRecorder.getCurrent().recordCpuUtilizationMetric(cpuUtilizationMetrics);
            CallMetricRecorder.getCurrent().recordMemoryUtilizationMetric(memoryUtilizationMetrics);
            SimpleResponse response =
                SimpleResponse.newBuilder().setResponseMessage("Simple response").build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
          }
        };

    ServerInterceptor metricReportingServerInterceptor = new OrcaMetricReportingServerInterceptor();
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(
                ServerInterceptors.intercept(simpleServiceImpl, metricReportingServerInterceptor))
            .build().start());

    ManagedChannel baseChannel =
        grpcCleanupRule.register(InProcessChannelBuilder.forName(serverName).build());
    channelToUse =
        ClientInterceptors.intercept(
            baseChannel, new TrailersCapturingClientInterceptor(trailersCapture));
  }

  @Test
  public void shareCallMetricRecorderInContext() throws IOException {
    final CallMetricRecorder callMetricRecorder =
        InternalCallMetricRecorder.newCallMetricRecorder();
    ServerStreamTracer.Factory callMetricRecorderSharingStreamTracerFactory =
        new ServerStreamTracer.Factory() {
      @Override
      public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
        return new ServerStreamTracer() {
          @Override
          public Context filterContext(Context context) {
            return context.withValue(InternalCallMetricRecorder.CONTEXT_KEY, callMetricRecorder);
          }
        };
      }
    };

    final AtomicReference<CallMetricRecorder> callMetricRecorderCapture = new AtomicReference<>();
    SimpleServiceGrpc.SimpleServiceImplBase simpleServiceImpl =
        new SimpleServiceGrpc.SimpleServiceImplBase() {
          @Override
          public void unaryRpc(
              SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            callMetricRecorderCapture.set(CallMetricRecorder.getCurrent());
            SimpleResponse response =
                SimpleResponse.newBuilder().setResponseMessage("Simple response").build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
          }
        };

    ServerInterceptor metricReportingServerInterceptor = new OrcaMetricReportingServerInterceptor();
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addStreamTracerFactory(callMetricRecorderSharingStreamTracerFactory)
            .addService(
                ServerInterceptors.intercept(simpleServiceImpl, metricReportingServerInterceptor))
            .build().start());

    ManagedChannel channel =
        grpcCleanupRule.register(InProcessChannelBuilder.forName(serverName).build());
    ClientCalls.blockingUnaryCall(channel, SIMPLE_METHOD, CallOptions.DEFAULT, REQUEST);

    assertThat(callMetricRecorderCapture.get()).isSameInstanceAs(callMetricRecorder);
  }

  @Test
  public void noTrailerReportIfNoRecordedMetrics() {
    ClientCalls.blockingUnaryCall(channelToUse, SIMPLE_METHOD, CallOptions.DEFAULT, REQUEST);
    Metadata receivedTrailers = trailersCapture.get();
    assertThat(
        receivedTrailers.get(OrcaMetricReportingServerInterceptor.ORCA_ENDPOINT_LOAD_METRICS_KEY))
        .isNull();
  }

  @Test
  public void responseTrailersContainAllReportedMetrics() {
    applicationCostMetrics.put("cost1", 1231.4543);
    applicationCostMetrics.put("cost2", 0.1367);
    applicationCostMetrics.put("cost3", 7614.145);
    applicationUtilizationMetrics.put("util1", 0.1082);
    applicationUtilizationMetrics.put("util2", 0.4936);
    applicationUtilizationMetrics.put("util3", 0.5342);
    cpuUtilizationMetrics = 0.3465;
    memoryUtilizationMetrics = 0.764;
    ClientCalls.blockingUnaryCall(channelToUse, SIMPLE_METHOD, CallOptions.DEFAULT, REQUEST);
    Metadata receivedTrailers = trailersCapture.get();
    OrcaLoadReport report =
        receivedTrailers.get(OrcaMetricReportingServerInterceptor.ORCA_ENDPOINT_LOAD_METRICS_KEY);
    assertThat(report.getUtilizationMap())
        .containsExactly("util1", 0.1082, "util2", 0.4936, "util3", 0.5342);
    assertThat(report.getRequestCostMap())
        .containsExactly("cost1", 1231.4543, "cost2", 0.1367, "cost3", 7614.145);
    assertThat(report.getCpuUtilization()).isEqualTo(0.3465);
    assertThat(report.getMemUtilization()).isEqualTo(0.764);
  }

  private static final class TrailersCapturingClientInterceptor implements ClientInterceptor {
    final AtomicReference<Metadata> trailersCapture;

    TrailersCapturingClientInterceptor(AtomicReference<Metadata> trailersCapture) {
      this.trailersCapture = trailersCapture;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      return new TrailersCapturingClientCall<>(next.newCall(method, callOptions));
    }

    private final class TrailersCapturingClientCall<ReqT, RespT>
        extends SimpleForwardingClientCall<ReqT, RespT> {

      TrailersCapturingClientCall(ClientCall<ReqT, RespT> call) {
        super(call);
      }

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        trailersCapture.set(null);
        super.start(new TrailersCapturingClientCallListener(responseListener), headers);
      }

      private final class TrailersCapturingClientCallListener
          extends SimpleForwardingClientCallListener<RespT> {
        TrailersCapturingClientCallListener(ClientCall.Listener<RespT> responseListener) {
          super(responseListener);
        }

        @Override
        public void onClose(Status status, Metadata trailers) {
          trailersCapture.set(trailers);
          super.onClose(status, trailers);
        }
      }
    }
  }
}
