package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExtProcProto;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor;
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorProto;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;

import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import javax.annotation.Nullable;
import java.util.concurrent.ScheduledExecutorService;

public class ExternalProcessorFilter implements Filter {
  static final String TYPE_URL = "type.googleapis.com/envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor";

  ManagedChannel extProcChannel;
  final String filterInstanceName;
  public ExternalProcessorFilter(String name) {
    filterInstanceName = checkNotNull(name, "name");
  }

  static final class Provider implements Filter.Provider {
    @Override
    public String[] typeUrls() {
      return new String[]{TYPE_URL};
    }

    @Override
    public boolean isClientFilter() {
      return true;
    }

    @Override
    public ExternalProcessorFilter newInstance(String name) {
      return new ExternalProcessorFilter(name);
    }

    @Override
    public ConfigOrError<ExternalProcessorFilterConfig> parseFilterConfig(Message rawProtoMessage) {
      if (!(rawProtoMessage instanceof Any)) {
        return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
      }
      ExternalProcessor externalProcessor;
      try {
        externalProcessor = ((Any) rawProtoMessage).unpack(ExternalProcessor.class);
      } catch (InvalidProtocolBufferException e) {
        return ConfigOrError.fromError("Invalid proto: " + e);
      }
      return ConfigOrError.fromConfig(new ExternalProcessorFilterConfig(externalProcessor));
    }

    @Override
    public ConfigOrError<? extends FilterConfig> parseFilterConfigOverride(Message rawProtoMessage) {
      return parseFilterConfig(rawProtoMessage);
    }
  }

  @Nullable
  @Override
  public ClientInterceptor buildClientInterceptor(FilterConfig filterConfig,
                                                  @Nullable FilterConfig overrideConfig, ScheduledExecutorService scheduler) {
    return new ExternalProcessorInterceptor(filterConfig, overrideConfig, scheduler);
  }

  static final class ExternalProcessorFilterConfig implements FilterConfig {

    private final ExternalProcessor externalProcessor;

    ExternalProcessorFilterConfig(ExternalProcessor externalProcessor) {
      this.externalProcessor = externalProcessor;
    }

    @Override
    public String typeUrl() {
      return "type.googleapis.com/envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor";
    }
  }

  static final class ExternalProcessorInterceptor implements ClientInterceptor {
    private final FilterConfig filterConfig;
    private final FilterConfig overrideConfig;
    private final ScheduledExecutorService scheduler;

    ExternalProcessorInterceptor(FilterConfig filterConfig,
                                 @Nullable FilterConfig overrideConfig, ScheduledExecutorService scheduler) {

      this.filterConfig = filterConfig;
      this.overrideConfig = overrideConfig;
      this.scheduler = scheduler;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next) {

      return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
        @Override
        public void sendMessage(ReqT message) {
          super.sendMessage(message);
        }
      };
    }
  }
}
