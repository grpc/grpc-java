/*
 * Copyright 2015, Google Inc. All rights reserved.
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

import io.grpc.Status;
import io.grpc.benchmarks.proto.Control;
import io.grpc.benchmarks.proto.WorkerServiceGrpc;
import io.grpc.internal.ServerImpl;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A load worker process which a driver can use to create clients and servers. The worker
 * implements the contract defined in 'control.proto'.
 */
public class LoadWorker {

  private static final Logger LOG = Logger.getLogger(LoadWorker.class.getName());

  private final int serverPort;
  private final ServerImpl driverServer;

  LoadWorker(int driverPort, int serverPort) throws Exception {
    this.serverPort = serverPort;
    this.driverServer = NettyServerBuilder.forPort(driverPort)
        .directExecutor()
        .addService(WorkerServiceGrpc.bindService(new WorkerServiceImpl()))
        .build();
  }

  public void start() throws Exception {
    driverServer.start();
  }

  /**
   * Start the load worker process.
   */
  public static void main(String[] argv) throws Exception {
    int driverPort = Integer.parseInt(argv[0]);
    int serverPort = argv.length > 1 ? Integer.parseInt(argv[1]) : 0;
    new LoadWorker(driverPort, serverPort).start();
  }

  /**
   * Implement the worker service contract which can launch clients and servers.
   */
  private class WorkerServiceImpl implements WorkerServiceGrpc.WorkerService {

    private LoadServer workerServer;
    private LoadClient workerClient;

    @Override
    public StreamObserver<Control.ServerArgs> runServer(
        final StreamObserver<Control.ServerStatus> responseObserver) {
      return new StreamObserver<Control.ServerArgs>() {
        @Override
        public void onNext(Control.ServerArgs value) {
          try {
            if (value.getSetup() != null && workerServer == null) {
              if (serverPort != 0 && value.getSetup().getPort() == 0) {
                Control.ServerArgs.Builder builder = value.toBuilder();
                builder.getSetupBuilder().setPort(serverPort);
                value = builder.build();
              }
              workerServer = new LoadServer(value.getSetup());
              workerServer.start();
              responseObserver.onNext(Control.ServerStatus.newBuilder()
                  .setPort(workerServer.getPort())
                  .setCores(workerServer.getCores())
                  .build());
            } else if (value.getMark() != null && workerServer != null) {
              responseObserver.onNext(Control.ServerStatus.newBuilder()
                  .setStats(workerServer.getStats())
                  .build());
            } else {
              responseObserver.onError(Status.ALREADY_EXISTS
                  .withDescription("Server already started")
                  .asRuntimeException());
            }
          } catch (Throwable t) {
            LOG.log(Level.WARNING, "Error running server", t);
            responseObserver.onError(Status.INTERNAL.withCause(t).asException());
            // Shutdown server if we can
            onCompleted();
          }
        }

        @Override
        public void onError(Throwable t) {
          LOG.log(Level.WARNING, "Error driving server", t);
          onCompleted();
        }

        @Override
        public void onCompleted() {
          if (workerServer != null) {
            try {
              workerServer.shutdownNow();
            } finally {
              workerServer = null;
            }
          }
        }
      };
    }

    @Override
    public StreamObserver<Control.ClientArgs> runClient(
        final StreamObserver<Control.ClientStatus> responseObserver) {
      return new StreamObserver<Control.ClientArgs>() {
        @Override
        public void onNext(Control.ClientArgs value) {
          try {
            if (value.getSetup() != null && workerClient == null) {
              workerClient = new LoadClient(value.getSetup());
              workerClient.start();
              responseObserver.onNext(Control.ClientStatus.newBuilder().build());
            } else if (value.getMark() != null && workerClient != null) {
              responseObserver.onNext(Control.ClientStatus.newBuilder()
                  .setStats(workerClient.getStats())
                  .build());
            } else {
              responseObserver.onError(Status.ALREADY_EXISTS
                  .withDescription("Client already started")
                  .asRuntimeException());
            }
          } catch (Throwable t) {
            LOG.log(Level.WARNING, "Error running client", t);
            responseObserver.onError(Status.INTERNAL.withCause(t).asException());
            // Shutdown the client if we can
            onCompleted();
          }
        }

        @Override
        public void onError(Throwable t) {
          LOG.log(Level.WARNING, "Error driving client", t);
          onCompleted();
        }

        @Override
        public void onCompleted() {
          if (workerClient != null) {
            try {
              workerClient.shutdownNow();
            } finally {
              workerClient = null;
            }
          }
        }
      };
    }

    @Override
    public void coreCount(Control.CoreRequest request,
                          StreamObserver<Control.CoreResponse> responseObserver) {
      responseObserver.onNext(Control.CoreResponse.newBuilder().setCores(1).build());
      responseObserver.onCompleted();
    }

    @Override
    public void quitWorker(Control.Void request,
                           StreamObserver<Control.Void> responseObserver) {
      try {
        responseObserver.onNext(Control.Void.getDefaultInstance());
        responseObserver.onCompleted();
        driverServer.shutdownNow();
      } catch (Throwable t) {
        LOG.log(Level.WARNING, "Error during shutdown", t);
      }
      System.exit(0);
    }
  }
}
