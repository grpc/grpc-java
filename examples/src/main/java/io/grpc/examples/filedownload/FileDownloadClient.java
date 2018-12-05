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

import com.google.common.io.ByteSink;
import com.google.common.io.FileWriteMode;
import com.google.common.io.Files;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simple client that requests a file download from the {@link FileDownloadServer}.
 */
public class FileDownloadClient {
  private static final Logger logger = Logger.getLogger(FileDownloadClient.class.getName());

  private final ManagedChannel channel;
  private final FileDownloadGrpc.FileDownloadBlockingStub blockingStub;

  /** Construct client connecting to HelloWorld server at {@code host:port}. */
  public FileDownloadClient(String host, int port) {
    this(ManagedChannelBuilder.forAddress(host, port)
        // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
        // needing certificates.
        .usePlaintext()
        .build());
  }

  /** Construct client for accessing FileDownload server using the existing channel. */
  FileDownloadClient(ManagedChannel channel) {
    this.channel = channel;
    blockingStub = FileDownloadGrpc.newBlockingStub(channel);
  }

  public void shutdown() throws InterruptedException {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  /** Download file from the server. */
  public void downloadFile(String url) throws Exception {
    logger.info("Will try to downloadFile " + url + " ...");
    FileDownloadRequest request = FileDownloadRequest.newBuilder().setUrl(url).build();
    File localTmpFile = File.createTempFile("localcopy", ".txt");

    ByteSink byteSink = Files.asByteSink(localTmpFile, FileWriteMode.APPEND);
    try {
      Iterator<DataChunk> response;
      try {
        response = blockingStub.download(request);
        while (response.hasNext()) {
          byteSink.write(response.next().getData().toByteArray());
        }
      } catch (StatusRuntimeException e) {
        logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
        return;
      }

      try (FileReader fr = new FileReader(localTmpFile);
           BufferedReader br = new BufferedReader(fr)) {
        String nextLine;
        while ((nextLine = br.readLine()) != null) {
          logger.info("File line: " + nextLine);
        }
      }

    } finally {
      localTmpFile.delete();
    }
  }

  /**
   * Greet server. If provided, the first element of {@code args} is the name to use in the
   * greeting.
   */
  public static void main(String[] args) throws Exception {
    FileDownloadClient client = new FileDownloadClient("localhost", 50051);
    try {
      /* Access a service running on the local machine on port 50051 */
      String url = "http://some-domain.com/myfile.txt";
      client.downloadFile(url);
    } finally {
      client.shutdown();
    }
  }
}
