package io.grpc;

import com.google.protobuf.MessageLiteOrBuilder;

public class RequestValidationListener<ReqT, ResT> extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {

    private ServerCall<ReqT, ResT> serverCall;
    private Metadata headers;
    private RequestValidatorResolver requestValidatorResolver;

    public RequestValidationListener(
            ServerCall.Listener<ReqT> delegate,
            ServerCall<ReqT, ResT> serverCall,
            Metadata headers,
            RequestValidatorResolver requestValidatorResolver
    ) {
        super(delegate);
        this.serverCall = serverCall;
        this.headers = headers;
        this.requestValidatorResolver = requestValidatorResolver;
    }

    @Override
    public void onMessage(ReqT message) {
        MessageLiteOrBuilder convertMessage = (MessageLiteOrBuilder) message;
        RequestValidator<MessageLiteOrBuilder> validator = requestValidatorResolver.find(convertMessage.getClass().getTypeName());

        if (validator == null) {
            super.onMessage(message);
        } else {
            try {
                ValidationResult validationResult = validator.isValid(convertMessage);

                if (validationResult.isValid()) {
                    super.onMessage(message);
                } else {
                    Status status = Status.INVALID_ARGUMENT
                            .withDescription("invalid argument. " + validationResult.getMessage());
                    handleInvalidRequest(status);
                }
            } catch (Exception e) {
                Status status = Status.INTERNAL.withDescription(e.getMessage());

                handleInvalidRequest(status);
            }
        }
    }

    private void handleInvalidRequest(Status status) {
        if (!serverCall.isCancelled()) {
            serverCall.close(status, headers);
        }
    }
}
