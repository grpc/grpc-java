/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc;

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
        RequestValidator<ReqT> validator = (RequestValidator<ReqT>) requestValidatorResolver.find(message.getClass());

        if (validator == null) {
            super.onMessage(message);
            return;
        }

        try {
            ValidationResult validationResult = validator.isValid(message);

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

    private void handleInvalidRequest(Status status) {
        if (!serverCall.isCancelled()) {
            serverCall.close(status, new Metadata());
        }
    }
}
