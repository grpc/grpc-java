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

package io.grpc.benchmarks.driver;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.benchmarks.Utils;
import io.grpc.benchmarks.proto.BenchmarkServiceGrpc;
import io.grpc.benchmarks.proto.Control;
import io.grpc.benchmarks.proto.Messages;
import io.grpc.benchmarks.proto.Stats;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.TestUtils;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements the server-side contract for the load testing scenarios.
 */
class LoadServer {
  private final Server server;
  private final BenchmarkServiceImpl benchmarkService;
  private final AtomicBoolean shutdown = new AtomicBoolean();
  private final int port;

  LoadServer(Control.ServerConfig config) throws Exception {
    port = config.getPort() ==  0 ? TestUtils.pickUnusedPort() : config.getPort();
    ServerBuilder<?> serverBuilder = ServerBuilder.forPort(port);
    switch (config.getServerType()) {
      case ASYNC_SERVER: {
        serverBuilder.executor(Executors.newFixedThreadPool(config.getAsyncServerThreads(),
            new DefaultThreadFactory("server-worker", true)));
        break;
      }
      case SYNC_SERVER: {
        serverBuilder.directExecutor();
        break;
      }
      case ASYNC_GENERIC_SERVER: {
        serverBuilder.executor(Executors.newFixedThreadPool(config.getAsyncServerThreads(),
            new DefaultThreadFactory("server-worker", true)));
        break;
      }
      default: {
        throw new IllegalArgumentException();
      }
    }
    if (config.hasSecurityParams()) {
      File cert = TestUtils.loadCert("server1.pem");
      File key = TestUtils.loadCert("server1.key");
      serverBuilder.useTransportSecurity(cert, key);
    }
    benchmarkService = new BenchmarkServiceImpl();
    serverBuilder.addService(BenchmarkServiceGrpc.bindService(benchmarkService));
    server = serverBuilder.build();
  }

  int getPort() {
    return port;
  }

  int getCores() {
    return Runtime.getRuntime().availableProcessors();
  }

  void start() throws Exception {
    server.start();
  }

  Stats.ServerStats getStats() {
    throw new UnsupportedOperationException();
  }

  void shutdownNow() {
    shutdown.set(true);
    server.shutdownNow();
  }

  private class BenchmarkServiceImpl implements BenchmarkServiceGrpc.BenchmarkService {

    @Override
    public void unaryCall(Messages.SimpleRequest request,
                          StreamObserver<Messages.SimpleResponse> responseObserver) {
      responseObserver.onNext(Utils.makeResponse(request));
      responseObserver.onCompleted();

    }

    @Override
    public StreamObserver<Messages.SimpleRequest> streamingCall(
        final StreamObserver<Messages.SimpleResponse> responseObserver) {
      return new StreamObserver<Messages.SimpleRequest>() {
        @Override
        public void onNext(Messages.SimpleRequest value) {
          if (!shutdown.get()) {
            responseObserver.onNext(Utils.makeResponse(value));
          } else {
            responseObserver.onCompleted();
          }
        }

        @Override
        public void onError(Throwable t) {
          responseObserver.onError(t);
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }
  }
}
