package io.grpc.examples.header;

import io.grpc.*;

import java.util.logging.Logger;

/**
 * interceptor to handle server header
 * @author zhaohaifeng
 * @since 2015-03-16
 */
public class HeaderServerInterceptor implements ServerInterceptor {

  private static final Logger logger = Logger.getLogger(HeaderServerInterceptor.class.getName());

  private static Metadata.Key<String> customHeadKey =
      Metadata.Key.of("custom_server_header_key", Metadata.ASCII_STRING_MARSHALLER);


  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(String method,
                                                               ServerCall<RespT> call,
                                                               final Metadata.Headers requestHeaders,
                                                               ServerCallHandler<ReqT, RespT> next) {
    logger.info("header received from client:" + requestHeaders.toString());
    return next.startCall(method, new ServerInterceptors.ForwardingServerCall<RespT>(call) {
      boolean sentHeaders=false;

      @Override
      public void sendHeaders(Metadata.Headers responseHeaders) {
        responseHeaders.put(customHeadKey,"customRespondValue");
        super.sendHeaders(responseHeaders);
        sentHeaders = true;
      }

      @Override
      public void sendPayload(RespT payload) {
        if (!sentHeaders) {
          sendHeaders(new Metadata.Headers());
        }
        super.sendPayload(payload);
      }

      @Override
      public void close(Status status, Metadata.Trailers trailers) {
        super.close(status, trailers);
      }
    }, requestHeaders);
  }
}
