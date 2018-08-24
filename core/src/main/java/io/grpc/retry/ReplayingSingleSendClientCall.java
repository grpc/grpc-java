package io.grpc.retry;

import com.google.common.base.Preconditions;

import io.grpc.ClientCall;
import io.grpc.Metadata;

/**
 * {@link ClientCall} for single send operations that captures the outgoing calls so they can be 
 * replayed on a different delegate.  {@link ReplayingSingleSendClientCall} is created with an 
 * initial delegate and that delegate is replaced whenever the ClientCall is replayed.
 * 
 * @param <ReqT> The request type
 * @param <RespT> The response type
 */
class ReplayingSingleSendClientCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {
    
    private ClientCall<ReqT, RespT> delegate;
    private io.grpc.ClientCall.Listener<RespT> responseListener;
    private Metadata headers;
    private ReqT message;
    private int numMessages;
    private boolean messageCompressionEnabled = false;

    public ReplayingSingleSendClientCall(ClientCall<ReqT, RespT> delegate) {
        this.delegate = delegate;
    }
    
    @Override
    public void start(io.grpc.ClientCall.Listener<RespT> responseListener, Metadata headers) {
        Preconditions.checkArgument(responseListener != null, "responseListener cannot be null");
        Preconditions.checkArgument(headers != null, "Headers cannot be null");
        this.responseListener = responseListener;
        this.headers = headers;
        this.delegate.start(responseListener, headers);
    }

    @Override
    public void request(int numMessages) {
        this.numMessages = numMessages;
        this.delegate.request(numMessages);
    }

    @Override
    public void cancel() {
        this.delegate.cancel();
    }

    @Override
    public void halfClose() {
        this.delegate.halfClose();
    }

    @Override
    public void sendMessage(ReqT message) {
        Preconditions.checkState(this.message == null, "Expecting only one message to be sent");
        this.message = message;
        this.delegate.sendMessage(message);
    }

    @Override
    public void setMessageCompression(boolean enabled) {
        this.messageCompressionEnabled = enabled;
    }
    
    @Override
    public boolean isReady() {
        return delegate.isReady();
    }

    public void replay(ClientCall<ReqT, RespT> delegate) {
        this.delegate = delegate;
        try {
            this.delegate.start(responseListener, headers);
            this.delegate.setMessageCompression(messageCompressionEnabled);
            this.delegate.request(numMessages);
            this.delegate.sendMessage(message);
            this.delegate.halfClose();
        } catch (Throwable t) {
            this.delegate.cancel();
        }
    }
}
