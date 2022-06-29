package io.grpc;

public class RequestValidationInterceptor implements ServerInterceptor {

    private RequestValidatorResolver requestValidatorResolver;

    public RequestValidationInterceptor(RequestValidatorResolver requestValidatorResolver) {
        this.requestValidatorResolver = requestValidatorResolver;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next
    ) {
        ServerCall.Listener<ReqT> delegate = next.startCall(call, headers);

        return new RequestValidationListener<ReqT, RespT>(delegate, call, headers, requestValidatorResolver);
    }
}
