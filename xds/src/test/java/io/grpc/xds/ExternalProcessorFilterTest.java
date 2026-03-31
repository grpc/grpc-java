package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation;
import io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.CommonResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation;
import io.envoyproxy.envoy.service.ext_proc.v3.HeadersResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.TrailersResponse;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.util.MutableHandlerRegistry;
import io.grpc.xds.ExternalProcessorFilter.ExternalProcessorFilterConfig;
import io.grpc.xds.ExternalProcessorFilter.ExternalProcessorInterceptor;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.EnvoyProtoData.Node;
import io.grpc.xds.internal.grpcservice.AllowedGrpcService;
import io.grpc.xds.internal.grpcservice.AllowedGrpcServices;
import io.grpc.xds.internal.grpcservice.CachedChannelManager;
import io.grpc.xds.internal.grpcservice.ChannelCredsConfig;
import io.grpc.xds.internal.grpcservice.ConfiguredChannelCredentials;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/**
 * Unit tests for {@link ExternalProcessorFilter}.
 */
@RunWith(JUnit4.class)
public class ExternalProcessorFilterTest {
  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final MutableHandlerRegistry dataPlaneServiceRegistry = new MutableHandlerRegistry();
  private final MutableHandlerRegistry extProcServiceRegistry = new MutableHandlerRegistry();

  private String dataPlaneServerName;
  private String extProcServerName;
  private ScheduledExecutorService scheduler;
  private ExternalProcessorFilter.Provider provider;
  private Filter.FilterContext filterContext;

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
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[1024];
        while ((nRead = stream.read(data, 0, data.length)) != -1) {
          buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return new String(buffer.toByteArray());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static class InProcessNameResolverProvider extends NameResolverProvider {
    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
      if ("in-process".equals(targetUri.getScheme())) {
        return new NameResolver() {
          @Override public String getServiceAuthority() { return "localhost"; }
          @Override public void start(Listener2 listener) {}
          @Override public void shutdown() {}
        };
      }
      return null;
    }
    @Override protected boolean isAvailable() { return true; }
    @Override protected int priority() { return 5; }
    @Override public String getDefaultScheme() { return "in-process"; }
    @Override public Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
      return Collections.emptyList();
    }
  }

  private static class InProcessChannelCredsConfig implements ChannelCredsConfig {
    @Override public String type() { return "inprocess"; }
  }

  @Before
  public void setUp() throws Exception {
    NameResolverRegistry.getDefaultRegistry().register(new InProcessNameResolverProvider());

    dataPlaneServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(dataPlaneServerName)
        .fallbackHandlerRegistry(dataPlaneServiceRegistry).directExecutor().build().start());

    extProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .fallbackHandlerRegistry(extProcServiceRegistry).directExecutor().build().start());

    scheduler = Executors.newSingleThreadScheduledExecutor();

    this.provider = new ExternalProcessorFilter.Provider();
    this.provider.newInstance("ext-proc");
    
    AllowedGrpcServices allowedServices =
        AllowedGrpcServices.create(
            ImmutableMap.of("in-process:" + extProcServerName,
                AllowedGrpcService.builder()
                    .configuredChannelCredentials(ConfiguredChannelCredentials.create(
                        InsecureChannelCredentials.create(), new InProcessChannelCredsConfig()))
                    .build()));

    Bootstrapper.ServerInfo serverInfo = Bootstrapper.ServerInfo.create(
        "xds-server", InsecureChannelCredentials.create());

    Bootstrapper.BootstrapInfo bootstrapInfo = Bootstrapper.BootstrapInfo.builder()
        .servers(ImmutableList.of(serverInfo))
        .node(Node.newBuilder().build())
        .allowedGrpcServices(Optional.of(allowedServices))
        .build();

    this.filterContext = Filter.FilterContext.builder()
        .bootstrapInfo(bootstrapInfo)
        .serverInfo(serverInfo)
        .build();
  }

  @After
  public void tearDown() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  private ExternalProcessorFilterConfig createFilterConfig() {
    GrpcService grpcService = GrpcService.newBuilder()
        .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
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

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        this.provider.parseFilterConfig(Any.pack(externalProcessor), filterContext);
        
    assertThat(configOrError.errorDetail).isNull();
    return configOrError.config;
  }

  @Test
  public void requestHeadersMutated() throws Exception {
    ExternalProcessorFilterConfig filterConfig = createFilterConfig();
    
    CachedChannelManager testChannelManager = new CachedChannelManager(config ->
        grpcCleanup.register(InProcessChannelBuilder.forName(extProcServerName).directExecutor().build())
    );
    
    ClientInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, testChannelManager, scheduler);

    // Register as INTERNAL interceptor
    Channel interceptedChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .directExecutor()
            .intercept(interceptor)
            .build());

    // Data Plane Server
    AtomicReference<Metadata> receivedHeaders = new AtomicReference<>();
    
    ServerServiceDefinition serviceDef = ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              receivedHeaders.set(receivedHeaders.get()); // Trigger any lazy evaluation
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
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              try { Thread.sleep(50); } catch (InterruptedException e) {}
              
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
                  .setRequestBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setBody(request.getRequestBody().getBody())
                              .build())
                          .build())
                      .build())
                  .build());
            } else if (request.hasResponseHeaders()) {
               responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder().build())
                      .build())
                  .build());
            } else if (request.hasResponseBody()) {
               responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setBody(request.getResponseBody().getBody())
                              .build())
                          .build())
                      .build())
                  .build());
            } else if (request.hasResponseTrailers()) {
               responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseTrailers(TrailersResponse.newBuilder().build())
                  .build());
            }
          }

          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    extProcServiceRegistry.addService(extProcImpl);

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<String> replyRef = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    
    ClientCalls.asyncUnaryCall(interceptedChannel.newCall(METHOD_SAY_HELLO, CallOptions.DEFAULT), "World", 
        new StreamObserver<String>() {
          @Override public void onNext(String value) { replyRef.set(value); }
          @Override public void onError(Throwable t) { errorRef.set(t); latch.countDown(); }
          @Override public void onCompleted() { latch.countDown(); }
        });

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    if (errorRef.get() != null) {
        throw new RuntimeException(errorRef.get());
    }

    assertThat(replyRef.get()).isEqualTo("Hello World");
    Metadata.Key<String> customHeaderKey = Metadata.Key.of("x-custom-header", Metadata.ASCII_STRING_MARSHALLER);
    assertThat(receivedHeaders.get().get(customHeaderKey)).isEqualTo("custom-value");
  }
}
