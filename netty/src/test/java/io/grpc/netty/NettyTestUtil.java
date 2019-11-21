/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.netty;

import static io.netty.util.CharsetUtil.UTF_8;

import com.google.common.io.ByteStreams;
import io.grpc.internal.ObjectPool;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.util.concurrent.Executor;

/**
 * Utility methods for supporting Netty tests.
 */
public class NettyTestUtil {

  static String toString(InputStream in) throws Exception {
    byte[] bytes = new byte[in.available()];
    ByteStreams.readFully(in, bytes);
    return new String(bytes, UTF_8);
  }

  static ByteBuf messageFrame(String message) throws Exception {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(os);
    dos.write(message.getBytes(UTF_8));
    dos.close();

    // Write the compression header followed by the context frame.
    return compressionFrame(os.toByteArray());
  }

  static ByteBuf compressionFrame(byte[] data) {
    ByteBuf buf = Unpooled.buffer();
    buf.writeByte(0);
    buf.writeInt(data.length);
    buf.writeBytes(data);
    return buf;
  }

  // A simple implementation of ObjectPool<Executor> that could track if the resource is returned.
  public static class TrackingObjectPoolForTest implements ObjectPool<Executor> {
    private boolean inUse;

    public TrackingObjectPoolForTest() { }

    @Override
    public Executor getObject() {
      inUse = true;
      return new Executor() {
        @Override
        public void execute(Runnable var1) { }
      };
    }

    @Override
    public Executor returnObject(Object object) {
      inUse = false;
      return null;
    }

    public boolean isInUse() {
      return this.inUse;
    }
  }
}
