/*
 * Copyright 2022 The gRPC Authors
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

public class RequestValidationListener<ReqT, ResT> extends ForwardingServerCallListener<ReqT> {

    private ServerCall<ReqT, ResT> call;
    private Metadata metadata;
    private ServerCallHandler<ReqT, ResT> next;
    private RequestValidatorResolver requestValidatorResolver;
    private final ServerCall.Listener<ReqT> NOOP_LISTENER = new ServerCall.Listener<ReqT>() {};

    private ServerCall.Listener<ReqT> delegate = NOOP_LISTENER;

    public RequestValidationListener(
            ServerCall<ReqT, ResT> call,
            Metadata metadata,
            ServerCallHandler<ReqT, ResT> next,
            RequestValidatorResolver requestValidatorResolver
    ) {
        this.call = call;
        this.metadata = metadata;
        this.next = next;
        this.requestValidatorResolver = requestValidatorResolver;
    }

    @Override
    protected ServerCall.Listener<ReqT> delegate() {
        return delegate;
    }

    @Override
    public void onMessage(ReqT message) {
        if (delegate != NOOP_LISTENER) {
            super.onMessage(message);
            return;
        }

        RequestValidator<ReqT> validator = (RequestValidator<ReqT>) requestValidatorResolver.find(message.getClass());

        if (validator != null) {
            ValidationResult validationResult = validator.isValid(message);

            if (!validationResult.isValid()) {
                Status status = Status.INVALID_ARGUMENT
                        .withDescription("invalid argument. " + validationResult.getMessage());

                handleInvalidRequest(status);

                return;
            }
        }

        delegate = next.startCall(call, metadata);
        super.onMessage(message);
    }

    private void handleInvalidRequest(Status status) {
        if (!call.isCancelled()) {
            call.close(status, new Metadata());
        }
    }
}
