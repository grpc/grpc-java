package io.grpc.examples.experimental;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

public class CompressionServerInterceptor implements ServerInterceptor {

    private static final String GZIP = "gzip";

    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
        ServerCallHandler<ReqT, RespT> next) {

        if (isGzipAcceptable(headers)) {
            call.setCompression(GZIP);
        }
        return next.startCall(call, headers);
    }

    /**
     * Adapt this method if other or multiple encodings are acceptable
     * 
     * @param headers
     * @return
     */
    private boolean isGzipAcceptable(Metadata headers) {
        return GZIP.equalsIgnoreCase(getASCIIHeader(headers, "grpc-accept-encoding"))
            && GZIP.equalsIgnoreCase(getASCIIHeader(headers, "accept-encoding"));
    }

    private String getASCIIHeader(Metadata headers, String headerName) {
        return headers.get(Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER));
    }

}
