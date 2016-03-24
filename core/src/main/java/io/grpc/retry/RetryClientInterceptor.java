/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.grpc.retry;

import io.grpc.retry.ReplayingSingleSendClientCall;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.Status.Code;

/**
 * Interceptor for retrying client calls.  Only UNAVAILABLE errors will be retried.  Retry is only
 * supported for method types where the client sends a single request.  For streaming responses
 * the call is not retried if at least one message was already read as this type of result has partial
 * state and should therefore be retried in application code.
 * 
 * Usage
 * <pre>
 * {code
    FooBlockingStub client = FooGrpc.newBlockingStub(channel);
    client
        .withInterceptors(new RetryClientInterceptor(Retryer.createDefault().maxRetrys(5)))
        .sayHello(HelloRequest.newBuilder().setName("foo").build());
 * }
 * </pre>
 */
public class RetryClientInterceptor implements ClientInterceptor {
    private final Retryer retryer;

    public RetryClientInterceptor(Retryer retryer) {
        this.retryer = retryer;
    }
    
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(final MethodDescriptor<ReqT, RespT> method,
            final CallOptions callOptions, final Channel next) {
        if (!method.getType().clientSendsOneMessage()) {
            return next.newCall(method, callOptions);
        }
        
        // TODO: Check if the method is immutable and retryable
        return new ReplayingSingleSendClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            Retryer instance = retryer;
            
            @Override
            public void start(io.grpc.ClientCall.Listener<RespT> responseListener, Metadata headers) {
                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                    private boolean receivedAResponse = false;
                    public void onClose(Status status, Metadata trailers) {
                        if (status.getCode() == Code.UNAVAILABLE
                            && !receivedAResponse
                            && instance.canRetry()) {
                            
                            instance = instance.retry(Context.current().wrap(new Runnable() {
                                @Override
                                public void run() {
                                    replay(next.newCall(method, callOptions));
                                }
                            }));
                        } else { 
                            instance.cancel();
                            super.onClose(status, trailers);
                        }
                    }
                    
                    @Override
                    public void onMessage(RespT message) {
                        receivedAResponse = true;
                        super.onMessage(message);
                    }

                }, headers);
            }
            
            @Override
            public void cancel() {
                instance.cancel();
                super.cancel();
            }
        };
    }
}
