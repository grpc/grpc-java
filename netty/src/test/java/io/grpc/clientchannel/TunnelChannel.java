package io.grpc.clientchannel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

class TunnelChannel extends AbstractChannel {

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final Consumer<ByteBuf> call;

    public TunnelChannel(Consumer<ByteBuf> call) {
        super(null);
        this.call = call;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) {
        Object msg;
        while ((msg = in.current()) != null) {
            ReferenceCountUtil.retain(msg);
            call.accept(((ByteBuf) msg).touch());
            in.remove();
        }
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new AbstractUnsafe() {
            @Override
            public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
                promise.setSuccess();
            }
        };
    }

    @Override
    public boolean isOpen() {
        return !closed.get();
    }

    @Override
    public boolean isActive() {
        return isOpen();
    }

    @Override
    protected void doClose() {
        closed.set(true);
    }

    //////////////////////////////////

    @Override
    protected void doBeginRead() {

    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    public ChannelConfig config() {
        return new DefaultChannelConfig(this);
    }

    @Override
    public ChannelMetadata metadata() {
        return new ChannelMetadata(false);
    }

    @Override
    protected void doBind(SocketAddress localAddress) {}

    @Override
    protected void doDisconnect() {}

    @Override
    protected SocketAddress localAddress0() {
        return null;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return null;
    }
}
