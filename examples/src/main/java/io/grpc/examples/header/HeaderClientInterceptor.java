package io.grpc.examples.header;

import io.grpc.*;

import java.util.logging.Logger;

/**
 * interceptor to handle client header
 * @author zhaohaifeng
 * @since 2015-03-16
 */
public class HeaderClientInterceptor implements ClientInterceptor {

  private static final Logger logger = Logger.getLogger(HeaderClientInterceptor.class.getName());

  private static Metadata.Key<String> customHeadKey =
      Metadata.Key.of("custom_client_header_key", Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <ReqT, RespT> Call<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                       Channel next) {
    return new ClientInterceptors.ForwardingCall<ReqT, RespT>(next.newCall(method)) {

      @Override
      public void start(Listener<RespT> responseListener, Metadata.Headers headers) {
        /* put custom header */
        headers.put(customHeadKey, "customRequestValue");
        super.start(new ClientInterceptors.ForwardingListener<RespT>(responseListener) {
          @Override
          public void onHeaders(Metadata.Headers headers) {
            /** if you don't need receive header from server,
             * you can use {@link io.grpc.stub.MetadataUtils attachHeaders} directly to send header**/
            logger.info("header received from server:" + headers.toString());
            super.onHeaders(headers);
          }
        }, headers);
      }
    };
  }
}
