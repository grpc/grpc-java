/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.android.integrationtest;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Funnels traffic between a given UDS endpoint and a local TCP endpoint. A client binds to the UDS
 * endpoint, but effectively communicates with the TCP endpoint.
 */
public class UdsTcpEndpointConnector {

  private static final String LOG_TAG = "EndpointConnector";

  // Discard-policy, to allow dropping tasks that were received immediately after shutDown()
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          5,
          10,
          1,
          SECONDS,
          new LinkedBlockingQueue<>(),
          new ThreadPoolExecutor.DiscardOldestPolicy());

  private final String udsPath;

  private final String host;
  private final int port;
  private InetSocketAddress socketAddress;

  private LocalServerSocket clientAcceptor;

  private volatile boolean shutDownRequested = false;
  private volatile boolean shutDownComplete = false;

  /** Listen on udsPath and forward connections to host:port. */
  public UdsTcpEndpointConnector(String udsPath, String host, int port) {
    this.udsPath = udsPath;
    this.host = host;
    this.port = port;
  }

  /** Start listening and accept connections. */
  public void start() throws IOException {
    clientAcceptor = new LocalServerSocket(udsPath);
    executor.execute(
        new Runnable() {
          @Override
          public void run() {
            Log.i(LOG_TAG, "Starting connection from " + udsPath + " to " + socketAddress);
            socketAddress = new InetSocketAddress(host, port);
            while (!shutDownRequested) {
              try {
                LocalSocket clientSocket = clientAcceptor.accept();
                if (shutDownRequested) {
                  // Check if shut down during blocking accept().
                  clientSocket.close();
                  shutDownComplete = true;
                  break;
                }
                Socket serverSocket = new Socket();
                serverSocket.connect(socketAddress);
                startWorkers(clientSocket, serverSocket);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            }
          }
        });
  }

  /** Stop listening and release resources. */
  public void shutDown() {
    Log.i(LOG_TAG, "Shutting down connection from " + udsPath + " to " + socketAddress);
    shutDownRequested = true;

    try {
      // Upon clientAcceptor.close(), clientAcceptor.accept() continues to block.
      // Thus, once shutDownRequested=true, must send a connection request to unblock accept().
      LocalSocket localSocket = new LocalSocket();
      localSocket.connect(clientAcceptor.getLocalSocketAddress());
      localSocket.close();
      clientAcceptor.close();
    } catch (IOException e) {
      Log.w(LOG_TAG, "Failed to close LocalServerSocket", e);
    }
    executor.shutdownNow();
  }

  public boolean isShutdown() {
    return shutDownComplete && executor.isShutdown();
  }

  private void startWorkers(LocalSocket clientSocket, Socket serverSocket) throws IOException {
    DataInputStream clientIn = new DataInputStream(clientSocket.getInputStream());
    DataOutputStream clientOut = new DataOutputStream(serverSocket.getOutputStream());
    DataInputStream serverIn = new DataInputStream(serverSocket.getInputStream());
    DataOutputStream serverOut = new DataOutputStream(clientSocket.getOutputStream());

    AtomicInteger completionCount = new AtomicInteger(0);
    StreamConnector.Listener cleanupListener =
        new StreamConnector.Listener() {
          @Override
          public void onFinished() {
            if (completionCount.incrementAndGet() == 2) {
              try {
                serverSocket.close();
                clientSocket.close();
              } catch (IOException e) {
                Log.e(LOG_TAG, "Failed to clean up connected sockets.", e);
              }
            }
          }
        };
    executor.execute(new StreamConnector(clientIn, clientOut).addListener(cleanupListener));
    executor.execute(new StreamConnector(serverIn, serverOut).addListener(cleanupListener));
  }

  /**
   * Funnels everything that comes in to a DataInputStream into an DataOutputStream, until the
   * DataInputStream is closed. (detected by IOException).
   */
  private static final class StreamConnector implements Runnable {

    interface Listener {
      void onFinished();
    }

    private static final int BUFFER_SIZE = 1000;

    private final DataInputStream in;
    private final DataOutputStream out;
    private final byte[] buffer = new byte[BUFFER_SIZE];

    private boolean finished = false;

    private final Collection<Listener> listeners = new ArrayList<>();

    StreamConnector(DataInputStream in, DataOutputStream out) {
      this.in = in;
      this.out = out;
    }

    StreamConnector addListener(Listener listener) {
      listeners.add(listener);
      return this;
    }

    @Override
    public void run() {
      while (!finished) {
        int bytesRead;
        try {
          bytesRead = in.read(buffer);
          if (bytesRead == -1) {
            finished = true;
            out.close();
            continue;
          }
          out.write(buffer, 0, bytesRead);
        } catch (IOException e) {
          finished = true;
        }
      }
      for (StreamConnector.Listener listener : listeners) {
        listener.onFinished();
      }
    }
  }
}
