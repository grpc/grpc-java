/*
 * Copyright 2016, Google Inc. All rights reserved.
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

package io.grpc.thrift;

import io.grpc.Status;

import org.apache.commons.io.IOUtils;
import org.apache.thrift.TBase;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nullable;

// Input stream for Thrift
class ThriftInputStream extends InputStream {

  // ThriftInputStream is initialized with a *message*
  @Nullable private TBase<?,?> message;
  @Nullable private ByteArrayInputStream partial;

  public ThriftInputStream(TBase<?,?> message) {
    this.message = message;
  }

  @Override
  public int read() throws IOException {
    // TODO Auto-generated method stub
    if (message != null) {
      TSerializer serializer = new TSerializer();
      try {
        partial = new ByteArrayInputStream(serializer.serialize(message));
        message = null;
      } catch (TException e) {
        // TODO Auto-generated catch block
        throw Status.INTERNAL.withDescription("Error in serializing Thrift Message")
            .withCause(e).asRuntimeException();
      }
    }
    if (partial != null) {
      return partial.read();
    }
    return -1;
  }

  TBase<?,?> message() {
    if (message != null) {
      if (partial == null) {
        throw new IllegalStateException("message not available");
      } else {
        try {
          byte[] bytes = IOUtils.toByteArray(partial);
          TDeserializer deserializer = new TDeserializer();
          deserializer.deserialize(message, bytes);
          return message;
        } catch (IOException e) {
          throw Status.INTERNAL.withDescription("Error in reading InputStream to bytes")
              .withCause(e).asRuntimeException();
        } catch (TException e) {
          // TODO Auto-generated catch block
          throw Status.INTERNAL.withDescription("Invalid Thrift byte Sequence")
              .withCause(e).asRuntimeException();
        }
      }
    }
    throw new IllegalStateException("message not avaible");
  }

}
