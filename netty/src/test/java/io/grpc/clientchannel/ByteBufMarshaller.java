package io.grpc.clientchannel;

import io.grpc.*;
import io.netty.buffer.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

class ByteBufMarshaller implements MethodDescriptor.Marshaller<ByteBuf> {

    static ByteBufMarshaller INSTANCE = new ByteBufMarshaller();

    @Override
    public InputStream stream(ByteBuf value) {
        return new DrainableInputStream(value);
    }

    @Override
    public ByteBuf parse(InputStream stream) {
        try {
            // See https://github.com/GoogleCloudPlatform/grpc-gcp-java/pull/77
            if (stream instanceof KnownLength) {
                int size = ((KnownLength) stream).available();

                if (size == 0) {
                    return Unpooled.EMPTY_BUFFER;
                }

                if (stream instanceof Detachable) {
                    Detachable detachable = (Detachable) stream;
                    stream = detachable.detach();
                }

                if (stream instanceof HasByteBuffer && ((HasByteBuffer) stream).byteBufferSupported()) {
                    HasByteBuffer hasByteBuffer = (HasByteBuffer) stream;
                    stream.mark(size);

                    ByteBuf firstBuffer = Unpooled.wrappedBuffer((hasByteBuffer.getByteBuffer()));
                    stream.skip(firstBuffer.readableBytes());

                    try {
                        // Skip composite buffer if the result fits into a single buffer
                        if (stream.available() <= 0) {
                            return firstBuffer;
                        }

                        CompositeByteBuf compositeBuffer = Unpooled.compositeBuffer(32);
                        compositeBuffer.addComponent(true, firstBuffer);

                        while (stream.available() != 0) {
                            ByteBuffer buffer = ((HasByteBuffer) stream).getByteBuffer();
                            ByteBuf byteBuf = Unpooled.wrappedBuffer(buffer);
                            compositeBuffer.addComponent(true, byteBuf);
                            stream.skip(buffer.remaining());
                        }

                        return compositeBuffer;
                    } finally {
                        stream.reset();
                    }
                }
            }

            ByteBuf buf = Unpooled.buffer(stream.available());
            buf.writeBytes(stream, stream.available());

            return buf;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    class DrainableInputStream extends ByteBufInputStream implements Drainable {

        private final ByteBuf buffer;

        public DrainableInputStream(ByteBuf buffer) {
            super(buffer);
            this.buffer = buffer;
        }

        @Override
        public int drainTo(OutputStream target) {
            int capacity = buffer.readableBytes();
            try {
                buffer.getBytes(buffer.readerIndex(), target, capacity);
                buffer.release();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return capacity;
        }
    }
}
