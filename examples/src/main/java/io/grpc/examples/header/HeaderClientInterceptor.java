package io.grpc.examples.header;

import io.grpc.*;

import java.util.logging.Logger;

/**
 * @author zhaohaifeng
 * @since 2015-03-16
 */
public class HeaderClientInterceptor implements ClientInterceptor {

  private static final Logger logger = Logger.getLogger(HeaderClientInterceptor.class.getName());

  @Override
  public <ReqT, RespT> Call<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                       Channel next) {
    return new ClientInterceptors.ForwardingCall<ReqT, RespT>(next.newCall(method)) {

      @Override
      public void start(Listener<RespT> responseListener, Metadata.Headers headers) {
        Metadata.Headers tmpHeaders = new Metadata.Headers();
        /* put custom header */
        Metadata.Key<String> headerKey = Metadata.Key.of("customRequestKey", Metadata.ASCII_STRING_MARSHALLER);
        tmpHeaders.put(headerKey, "customRequestValue");
        headers.merge(tmpHeaders);
        super.start(new ClientInterceptors.ForwardingListener<RespT>(responseListener) {
          @Override
          public void onHeaders(Metadata.Headers headers) {
            logger.info("header received from server:" + headers.toString());
            super.onHeaders(headers);
          }
        }, headers);
      }
    };
  }
}
