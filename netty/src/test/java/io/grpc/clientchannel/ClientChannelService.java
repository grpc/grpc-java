package io.grpc.clientchannel;

import io.grpc.*;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public abstract class ClientChannelService implements BindableService {

    static String TUNNEL_SERVICE = "io.grpc.Tunnel";

    static MethodDescriptor<ByteBuf, ByteBuf> NEW_TUNNEL_METHOD = MethodDescriptor
            .newBuilder(ByteBufMarshaller.INSTANCE, ByteBufMarshaller.INSTANCE)
            .setFullMethodName(TUNNEL_SERVICE + "/new")
            .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
            .build();

    public static void registerServer(
            ManagedChannel networkChannel,
            Metadata headers,
            Consumer<ServerBuilder<?>> serverBuilderConsumer
    ) {
        ClientCall<ByteBuf, ByteBuf> serverCall = networkChannel.newCall(NEW_TUNNEL_METHOD, CallOptions.DEFAULT);

        DefaultEventLoopGroup eventLoopGroup = new DefaultEventLoopGroup();

        TunnelChannel channel = new TunnelChannel(serverCall::sendMessage);

        NettyServerBuilder nettyServerBuilder = NettyServerBuilder
                .forAddress(new LocalAddress("clientchannel-" + System.nanoTime()))
                .workerEventLoopGroup(eventLoopGroup)
                .bossEventLoopGroup(eventLoopGroup);
        serverBuilderConsumer.accept(nettyServerBuilder);

        Server server = nettyServerBuilder
                .withOption(ChannelOption.SO_KEEPALIVE, null)
                .withOption(ChannelOption.AUTO_READ, true)
                .withOption(ChannelOption.AUTO_CLOSE, false)
                .channelFactory(() -> {
                    return new LocalServerChannel() {
                        @Override
                        protected void doBeginRead() {
                            pipeline().fireChannelRead(channel);
                        }
                    };
                })
                .build();

        try {
            server.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        serverCall.start(
                new ClientCall.Listener<ByteBuf>() {

                    @Override
                    public void onReady() {
                        serverCall.request(Integer.MAX_VALUE);
                    }

                    @Override
                    public void onMessage(ByteBuf bytes) {
                        if (bytes.readableBytes() > 0) {
                            channel.pipeline().fireChannelRead(bytes);
                        }
                    }

                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        server.shutdown();
                    }
                },
                headers
        );
    }

    abstract protected void onChannel(ManagedChannel channel, Metadata headers);

    @Override
    public ServerServiceDefinition bindService() {
        return ServerServiceDefinition
                .builder(TUNNEL_SERVICE)
                .addMethod(NEW_TUNNEL_METHOD, new TunnelHandler(this))
                .build();
    }

    static class TunnelHandler implements ServerCallHandler<ByteBuf, ByteBuf> {

        private final ClientChannelService tunnelClientChannelService;

        private final AtomicLong id = new AtomicLong();

        public TunnelHandler(ClientChannelService tunnelClientChannelService) {
            this.tunnelClientChannelService = tunnelClientChannelService;
        }

        @Override
        public ServerCall.Listener<ByteBuf> startCall(ServerCall<ByteBuf, ByteBuf> call, Metadata headers) {
            try {
                call.sendHeaders(new Metadata());
                call.request(Integer.MAX_VALUE);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            TunnelChannel nettyChannel = new TunnelChannel(call::sendMessage);
            DefaultEventLoopGroup eventLoopGroup = new DefaultEventLoopGroup();

            ManagedChannel grpcChannel = NettyChannelBuilder
                    .forAddress(new LocalAddress("tunnel-" + id.incrementAndGet()))
                    .eventLoopGroup(eventLoopGroup)
                    .directExecutor()
                    .channelFactory(() -> nettyChannel)
                    .withOption(ChannelOption.SO_KEEPALIVE, null)
                    .withOption(ChannelOption.AUTO_READ, false)
                    .withOption(ChannelOption.AUTO_CLOSE, false)
                    .usePlaintext()
                    .build();

            tunnelClientChannelService.onChannel(grpcChannel, headers);

            return new ServerCall.Listener<ByteBuf>() {

                @Override
                public void onMessage(ByteBuf byteBuf) {
                    if (byteBuf.readableBytes() > 0) {
                        nettyChannel.pipeline().fireChannelRead(byteBuf);
                    }
                }

                @Override
                public void onHalfClose() {
                    onCancel();
                }

                @Override
                public void onComplete() {
                    onCancel();
                }

                @Override
                public void onCancel() {
                    grpcChannel.shutdown();
                    eventLoopGroup.shutdownGracefully();
                }
            };
        }
    }
}
