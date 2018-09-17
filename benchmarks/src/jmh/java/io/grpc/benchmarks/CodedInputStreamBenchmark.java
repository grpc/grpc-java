/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.benchmarks;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Message;
import io.grpc.codedinputstreambenchmark.Messages.BenchmarkProto;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Benchmarks the performance of {@link CodedInputStream} across various sources of input data and
 * message sizes.
 */
@State(Scope.Benchmark)
public class CodedInputStreamBenchmark {

  /** The source of data used to drive the {@link CodedInputStream}. */
  public enum SourceType {
    ARRAY() {
      @Override
      Strategy newStrategy(byte[] buffer) {
        return new ArrayStrategy(buffer);
      }
    },
    NIO_HEAP {
      @Override
      Strategy newStrategy(byte[] buffer) {
        return new NioStrategy(buffer, false);
      }
    },
    NIO_DIRECT {
      @Override
      Strategy newStrategy(byte[] buffer) {
        return new NioStrategy(buffer, true);
      }
    },
    STREAM {
      @Override
      Strategy newStrategy(byte[] buffer) {
        return new StreamStrategy(buffer);
      }
    },
    ITER_DIRECT {
      @Override
      Strategy newStrategy(byte[] buffer) {
        return new IterBuffStrategy(buffer, true);
      }
    };

    abstract Strategy newStrategy(byte[] buffer);
  }

  /** The size of the message to be parsed. */
  public enum MessageType {
    MESSAGE_1_100k(1, 100 * 1024),
    MESSAGE_100_1k(100, 1024),
    MESSAGE_1_1m(1, 1024 * 1024),
    MESSAGE_1_4m(1, 4 * 1024 * 1024);

    private final Message message;

    MessageType(int count, int size) {
      BenchmarkProto.Builder it =
          BenchmarkProto.newBuilder();
      for (int i = 0; i < count; i++) {
        byte[] bytes = new byte[size];
        it.addBytes(ByteString.copyFrom(bytes));
      }
      message = it.build();
    }

    final Message getMessage() {
      return message;
    }
  }

  @Param({
      "ARRAY",
      "NIO_HEAP",
      "NIO_DIRECT",
      "STREAM",
      "ITER_DIRECT"})
  public SourceType sourceType;

  @Param({"MESSAGE_1_100k", "MESSAGE_100_1k", "MESSAGE_1_1m", "MESSAGE_1_4m"})
  public MessageType messageType;

  private Strategy strategy;

  @Setup
  public void setUp() {
    byte[] serializedMessage = messageType.getMessage().toByteArray();
    strategy = sourceType.newStrategy(serializedMessage);
  }

  @Benchmark
  @BenchmarkMode(Mode.SampleTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public Object readMessage() throws IOException {
    return messageType.getMessage().newBuilderForType().mergeFrom(strategy.newCodedInput()).build();
  }

  @TearDown
  public void tearDown() {
    strategy.reset();
  }

  private interface Strategy {
    CodedInputStream newCodedInput();

    void reset();
  }

  private static final class ArrayStrategy implements Strategy {
    private final byte[] message;

    ArrayStrategy(byte[] message) {
      this.message = message;
    }

    @Override
    public CodedInputStream newCodedInput() {
      return CodedInputStream.newInstance(message);
    }

    @Override
    public void reset() {
      // Nothing to do.
    }
  }

  private static final class StreamStrategy implements Strategy {
    private byte[] data;

    StreamStrategy(byte[] message) {
      this.data = message;
    }

    @Override
    public CodedInputStream newCodedInput() {
      return CodedInputStream.newInstance(new ByteArrayInputStream(data));
    }

    @Override
    public void reset() {
    }
  }

  private static final class NioStrategy implements Strategy {
    private final ByteBuffer buffer;

    NioStrategy(byte[] message, boolean direct) {
      if (direct) {
        buffer = ByteBuffer.allocateDirect(message.length);
        buffer.put(message);
        buffer.flip();
      } else {
        buffer = ByteBuffer.wrap(message);
      }
    }

    @Override
    public CodedInputStream newCodedInput() {
      return CodedInputStream.newInstance(buffer);
    }

    @Override
    public void reset() {
      buffer.clear();
    }
  }

  private static final class IterBuffStrategy implements Strategy {
    private final ArrayList<ByteBuffer> input;
    private int chunkSize;

    IterBuffStrategy(byte[] message, boolean direct) {
      chunkSize = 4096;
      input = new ArrayList<ByteBuffer>();
      for (int haveDealed = 0; haveDealed < message.length; haveDealed += chunkSize) {
        int rl = Math.min(chunkSize, message.length - haveDealed);
        if (direct) {
          ByteBuffer temp = ByteBuffer.allocateDirect(rl);
          temp.put(message, haveDealed, rl);
          temp.flip();
          input.add(temp);
        } else {
          input.add(ByteBuffer.wrap(message, haveDealed, rl));
        }
      }
    }

    @Override
    public CodedInputStream newCodedInput() {
      return CodedInputStream.newInstance(input);
    }

    @Override
    public void reset() {
      // Nothing to do
    }
  }
}
