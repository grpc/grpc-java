/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc.internal;

import io.grpc.Attributes;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;
import javax.annotation.Nullable;

/**
 * A {@link ServerCall} that wraps around a non thread safe delegate and provides thread safe
 * access.
 */
public class SynchronizedServerCall<ReqT, RespT> extends
    ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {

  public SynchronizedServerCall(ServerCall<ReqT, RespT> delegate) {
    super(delegate);
  }

  @Override
  public synchronized void sendMessage(RespT message) {
    super.sendMessage(message);
  }

  @Override
  public synchronized void request(int numMessages) {
    super.request(numMessages);
  }

  @Override
  public synchronized void sendHeaders(Metadata headers) {
    super.sendHeaders(headers);
  }

  @Override
  public synchronized boolean isReady() {
    return super.isReady();
  }

  @Override
  public synchronized void close(Status status, Metadata trailers) {
    super.close(status, trailers);
  }

  @Override
  public synchronized boolean isCancelled() {
    return super.isCancelled();
  }

  @Override
  public synchronized void setMessageCompression(boolean enabled) {
    super.setMessageCompression(enabled);
  }

  @Override
  public synchronized void setCompression(String compressor) {
    super.setCompression(compressor);
  }

  @Override
  public synchronized Attributes getAttributes() {
    return super.getAttributes();
  }

  @Nullable
  @Override
  public synchronized String getAuthority() {
    return super.getAuthority();
  }
}
