/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

package io.grpc.services;

import io.grpc.BinaryLog;
import io.grpc.ClientInterceptor;
import io.grpc.ServerInterceptor;
import java.io.IOException;
import javax.annotation.Nullable;

public class BinaryLogImpl extends BinaryLog {
  private final BinaryLogInterceptor.Factory methodInterceptorFactory;
  private final BinaryLogSink sink;

  public BinaryLogImpl() {
    this(BinaryLogSinkProvider.provider(), System.getenv("GRPC_BINARY_LOG_CONFIG"));
  }

  BinaryLogImpl(BinaryLogSink sink, String configStr) {
    this.sink = sink;
    if (sink == null || configStr == null || configStr.length() == 0) {
      methodInterceptorFactory = null;
    } else {
      methodInterceptorFactory = new BinaryLogInterceptor.Factory(sink, configStr);
    }
  }

  @Nullable
  @Override
  public ServerInterceptor getServerInterceptor(String fullMethodName) {
    return methodInterceptorFactory.getInterceptor(fullMethodName);
  }

  @Nullable
  @Override
  public ClientInterceptor getClientInterceptor(String fullMethodName) {
    return methodInterceptorFactory.getInterceptor(fullMethodName);
  }

  @Override
  protected int priority() {
    return 5;
  }

  @Override
  protected boolean isAvailable() {
    return methodInterceptorFactory != null;
  }

  @Override
  public void close() throws IOException {
    sink.close();
  }
}
