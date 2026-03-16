package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;

import com.google.protobuf.Any;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.CommonResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation;
import io.envoyproxy.envoy.service.ext_proc.v3.HeadersResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.util.MutableHandlerRegistry;
import io.grpc.xds.internal.grpcservice.ChannelCredsConfig;
import io.grpc.xds.internal.grpcservice.ConfiguredChannelCredentials;
import io.grpc.xds.internal.grpcservice.GrpcServiceXdsContext;
import io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextProvider;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link ExternalProcessorFilter}.
 */
@RunWith(JUnit4.class)
public class ExternalProcessorFilterTest {
  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final MutableHandlerRegistry dataPlaneServiceRegistry = new MutableHandlerRegistry();
  private final MutableHandlerRegistry extProcServiceRegistry = new MutableHandlerRegistry();

  private Channel dataPlaneChannel;
  private String extProcServerName;
  private ExternalProcessorFilter filter;

  // Define a simple test service
  private static final MethodDescriptor<String, String> METHOD_SAY_HELLO =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("test.TestService/SayHello")
          .setRequestMarshaller(new StringMarshaller())
          .setResponseMarshaller(new StringMarshaller())
          .build();

  private static class StringMarshaller implements MethodDescriptor.Marshaller<String> {
    @Override
    public InputStream stream(String value) {
      return new ByteArrayInputStream(value.getBytes());
    }

    @Override
    public String parse(InputStream stream) {
      try {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[1024];
        while ((nRead = stream.read(data, 0, data.length)) != -1) {
          buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return new String(buffer.toByteArray());
      } catch (java.io.IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static class InProcessChannelCredsConfig implements ChannelCredsConfig {
    @Override
    public String type() {
      return "inprocess";
    }
  }

  @Before
  public void setUp() throws Exception {
    String dataPlaneServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(dataPlaneServerName)
        .fallbackHandlerRegistry(dataPlaneServiceRegistry).directExecutor().build().start());

    extProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .fallbackHandlerRegistry(extProcServiceRegistry).directExecutor().build().start());

    dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());
  }

  private ExternalProcessorFilter.ExternalProcessorFilterConfig createFilterConfig() {
    GrpcService grpcService = GrpcService.newBuilder()
        .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
            // Important: Use "in-process:" scheme so Grpc.newChannelBuilder resolves it correctly
            .setTargetUri("in-process:" + extProcServerName)
            .setStatPrefix("ext_proc")
            .build())
        .build();

    ExternalProcessor externalProcessor = ExternalProcessor.newBuilder()
        .setGrpcService(grpcService)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .build())
        .build();

    ExternalProcessorFilter.Provider provider = new ExternalProcessorFilter.Provider();
    
    // Provide a context that supplies Insecure credentials for testing
    GrpcServiceXdsContextProvider contextProvider = targetUri -> {
        ConfiguredChannelCredentials credentials = ConfiguredChannelCredentials.create(
            InsecureChannelCredentials.create(),
            new InProcessChannelCredsConfig());
        
        GrpcServiceXdsContext.AllowedGrpcService allowedGrpcService = 
            GrpcServiceXdsContext.AllowedGrpcService.builder()
                .configuredChannelCredentials(credentials)
                .build();
        return GrpcServiceXdsContext.create(false, Optional.of(allowedGrpcService), true);
    };
    
    // 1. Create the filter instance via the provider
    this.filter = provider.newInstance("ext-proc", contextProvider);
    
    // 2. Parse the config using the provider
    ConfigOrError<ExternalProcessorFilter.ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(externalProcessor));
        
    assertThat(configOrError.errorDetail).isNull();
    return configOrError.config;
  }

  @Test
  public void requestHeadersMutated() throws Exception {
    ExternalProcessorFilter.ExternalProcessorFilterConfig filterConfig = createFilterConfig();
    
    // Use the filter instance created in createFilterConfig()
    ClientInterceptor interceptor = filter.buildClientInterceptor(filterConfig, null, null);
    Channel interceptedChannel = ClientInterceptors.intercept(dataPlaneChannel, interceptor);

    // Data Plane Server
    AtomicReference<Metadata> receivedHeaders = new AtomicReference<>();
    
    ServerServiceDefinition serviceDef = ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build();

    ServerServiceDefinition interceptedServiceDef = ServerInterceptors.intercept(
        serviceDef,
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            receivedHeaders.set(headers);
            return next.startCall(call, headers);
          }
        });
        
    dataPlaneServiceRegistry.addService(interceptedServiceDef);

    // Ext-Proc Server
    List<ProcessingRequest> receivedRequests = new ArrayList<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            receivedRequests.add(request);
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setHeaderMutation(HeaderMutation.newBuilder()
                              .addSetHeaders(io.envoyproxy.envoy.config.core.v3.HeaderValueOption.newBuilder()
                                  .setHeader(io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                                      .setKey("x-custom-header")
                                      .setValue("custom-value")
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
            } else if (request.hasRequestBody()) {
               responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestBody(BodyResponse.newBuilder().build())
                  .build());
            }
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };
    extProcServiceRegistry.addService(extProcImpl);

    String reply = ClientCalls.blockingUnaryCall(interceptedChannel, METHOD_SAY_HELLO, CallOptions.DEFAULT, "World");

    assertThat(reply).isEqualTo("Hello World");
    Metadata.Key<String> customHeaderKey = Metadata.Key.of("x-custom-header", Metadata.ASCII_STRING_MARSHALLER);
    assertThat(receivedHeaders.get().get(customHeaderKey)).isEqualTo("custom-value");
  }
}
