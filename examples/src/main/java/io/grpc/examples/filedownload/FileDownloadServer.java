/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.examples.filedownload;

import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Server that manages startup/shutdown of a {@code FileDownload} server.
 */
public class FileDownloadServer {
  private static final Logger logger = Logger.getLogger(FileDownloadServer.class.getName());

  private Server server;

  private void start() throws IOException {
    /* The port on which the server should run */
    int port = 50051;
    server = ServerBuilder.forPort(port)
      .addService(new FileDownloadImpl())
      .build()
      .start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        FileDownloadServer.this.stop();
        System.err.println("*** server shut down");
      }
    });
  }

  private void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  /**
   * Await termination on the main thread since the grpc library uses daemon threads.
   */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  /**
   * Main launches the server from the command line.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    final FileDownloadServer server = new FileDownloadServer();
    server.start();
    server.blockUntilShutdown();
  }

  static class FileDownloadImpl extends FileDownloadGrpc.FileDownloadImplBase {
    @Override
    public void download(FileDownloadRequest request, StreamObserver<DataChunk> responseObserver) {
      File tmpFile = null;
      try {
        tmpFile = File.createTempFile("tmp_file_for_demo_purposes", ".txt");
        try (FileWriter fw = new FileWriter(tmpFile);
             BufferedWriter bw = new BufferedWriter(fw)) {
          int numBytes = 0;
          while (numBytes < 256 * 1024 * 4) { // 1024k
            bw.write(request.getUrl());
            bw.write("\n");
            numBytes += request.getUrl().length();
          }
        }
        try (FileInputStream fis = new FileInputStream(tmpFile);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
          int bufferSize = 256 * 1024;// 256k
          byte[] buffer = new byte[bufferSize];
          int length;
          while ((length = bis.read(buffer, 0, bufferSize)) != -1) {
            responseObserver.onNext(
              DataChunk.newBuilder().setData(ByteString.copyFrom(buffer, 0, length)).build()
            );
          }
          responseObserver.onCompleted();
        }
      } catch (IOException e) {
        e.printStackTrace();
      } finally {
        if (tmpFile != null) {
          tmpFile.delete();
        }
      }
    }
  }
}
