package io.grpc.examples.helloworld;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public class LoadBalancer {

  static class BackendServer {
    final String name;
    final String host;
    final int port;
    int activeConnections;

    BackendServer(String name, String host, int port) {
      this.name = name;
      this.host = host;
      this.port = port;
      this.activeConnections = 0;
    }
  }

  private final int listenPort;
  private final List<BackendServer> backends;

  public LoadBalancer(int listenPort, List<BackendServer> backends) {
    this.listenPort = listenPort;
    this.backends = Collections.synchronizedList(backends);
  }

  public void start() throws IOException {
    try (ServerSocket serverSocket = new ServerSocket(listenPort)) {
      System.out.println("[LB] Listening on port " + listenPort);

      while (true) {
        Socket clientSocket = serverSocket.accept();
        BackendServer backend = chooseLeastConnectionsBackend();

        if (backend == null) {
          try (PrintWriter out = new PrintWriter(
              new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            out.println("ERROR|code=NO_BACKEND;message=No backend server available");
          } catch (Exception ignored) {
          } finally {
            try { clientSocket.close(); } catch (Exception ignored) {}
          }
          continue;
        }

        incrementConnections(backend);

        Thread t = new Thread(() -> {
          try {
            handleClient(clientSocket, backend);
          } finally {
            decrementConnections(backend);
          }
        }, "lb-client-" + clientSocket.getPort());

        t.start();
      }
    }
  }

  private BackendServer chooseLeastConnectionsBackend() {
    synchronized (backends) {
      BackendServer best = null;
      for (BackendServer b : backends) {
        if (best == null || b.activeConnections < best.activeConnections) {
          best = b;
        }
      }
      return best;
    }
  }

  private void incrementConnections(BackendServer backend) {
    synchronized (backends) {
      backend.activeConnections++;
      System.out.println("[LB] Routed new client to " + backend.name
          + " (" + backend.host + ":" + backend.port + ")"
          + " active=" + backend.activeConnections);
    }
  }

  private void decrementConnections(BackendServer backend) {
    synchronized (backends) {
      if (backend.activeConnections > 0) {
        backend.activeConnections--;
      }
      System.out.println("[LB] Client disconnected from " + backend.name
          + " active=" + backend.activeConnections);
    }
  }

  private void handleClient(Socket clientSocket, BackendServer backend) {
    Socket backendSocket = null;

    try (
        BufferedReader clientIn = new BufferedReader(
            new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter clientOut = new PrintWriter(
            new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true)
    ) {
      backendSocket = new Socket();
      backendSocket.connect(new InetSocketAddress(backend.host, backend.port), 3000);

      try (
          BufferedReader backendIn = new BufferedReader(
              new InputStreamReader(backendSocket.getInputStream(), StandardCharsets.UTF_8));
          PrintWriter backendOut = new PrintWriter(
              new OutputStreamWriter(backendSocket.getOutputStream(), StandardCharsets.UTF_8), true)
      ) {
        // Forward backend greeting first
        String greeting = backendIn.readLine();
        if (greeting != null) {
          clientOut.println(greeting);
        } else {
          clientOut.println("ERROR|code=BACKEND_DOWN;message=Backend did not respond");
          return;
        }

        String line;
        while ((line = clientIn.readLine()) != null) {
          line = line.trim();
          if (line.isEmpty()) continue;

          System.out.println("[LB] client=" + clientSocket.getRemoteSocketAddress()
              + " -> " + backend.name + " request=" + line);

          backendOut.println(line);

          String response = backendIn.readLine();
          if (response == null) {
            clientOut.println("ERROR|code=BACKEND_DOWN;message=Backend connection lost");
            break;
          }

          clientOut.println(response);

          if (line.startsWith("QUIT")) {
            break;
          }
        }
      }
    } catch (Exception e) {
      System.out.println("[LB] Handler error: " + e.getMessage());
      try {
        PrintWriter clientOut = new PrintWriter(
            new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true);
        clientOut.println("ERROR|code=LB_EXCEPTION;message=" + safe(e.getMessage()));
      } catch (Exception ignored) {
      }
    } finally {
      try { clientSocket.close(); } catch (Exception ignored) {}
      try {
        if (backendSocket != null) backendSocket.close();
      } catch (Exception ignored) {}
    }
  }

  private static String safe(String msg) {
    if (msg == null) return "";
    return msg.replace("\n", " ").replace("\r", " ");
  }

  public static void main(String[] args) throws Exception {
    int listenPort = 50100;
    if (args.length >= 1) {
      try {
        listenPort = Integer.parseInt(args[0]);
      } catch (Exception ignored) {
        listenPort = 50100;
      }
    }

    List<BackendServer> backends = new ArrayList<>();
    backends.add(new BackendServer("backend-1", "localhost", 50052));
    backends.add(new BackendServer("backend-2", "localhost", 50062));
    backends.add(new BackendServer("backend-3", "localhost", 50072));

    LoadBalancer lb = new LoadBalancer(listenPort, backends);
    lb.start();
  }
}
