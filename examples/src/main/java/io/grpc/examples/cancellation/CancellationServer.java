/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.examples.cancellation;

import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.examples.echo.EchoGrpc;
import io.grpc.examples.echo.EchoRequest;
import io.grpc.examples.echo.EchoResponse;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Server that manages startup/shutdown of a {@code Greeter} server.
 *
 * <p>Any abort of an ongoing RPC is considered "cancellation" of that RPC. The common causes of
 * cancellation are the client explicitly cancelling, the deadline expires, and I/O failures. The
 * service is not informed the reason for the cancellation.
 *
 * <p>There are two APIs for services to be notified of RPC cancellation: io.grpc.Context and
 * ServerCallStreamObserver. Context listeners are called on a different thread, so need to be
 * thread-safe. The ServerCallStreamObserver cancellation callback is called like other
 * StreamObserver callbacks, so the application may not need thread-safe handling. Both APIs have
 * thread-safe isCancelled() polling methods.
 */
public class CancellationServer {
  public static void main(String[] args) throws IOException, InterruptedException {
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    int port = 50051;
    Server server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
        .addService(new SlowEcho(scheduler))
        .build()
        .start();
    System.out.println("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        try {
          server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          e.printStackTrace(System.err);
        }
      }
    });
    server.awaitTermination();
    scheduler.shutdown();
    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
      scheduler.shutdownNow();
    }
  }

  static class SlowEcho extends EchoGrpc.EchoImplBase {
    private final ScheduledExecutorService scheduler;

    /** {@code scheduler} must be single-threaded. */
    public SlowEcho(ScheduledExecutorService scheduler) {
      this.scheduler = scheduler;
    }

    /**
     * Repeatedly echos each request until the client has no more requests. It performs all work
     * asynchronously on a single thread. It uses ServerCallStreamObserver to be notified of RPC
     * cancellation.
     */
    @Override
    public StreamObserver<EchoRequest> bidirectionalStreamingEcho(
        StreamObserver<EchoResponse> responseObserver) {
      // If the service is truly asynchronous, using ServerCallStreamObserver to receive
      // cancellation notifications tends to work well.

      // It is safe to cast the provided observer to ServerCallStreamObserver.
      ServerCallStreamObserver<EchoResponse> responseCallObserver =
          (ServerCallStreamObserver<EchoResponse>) responseObserver;
      System.out.println("\nBidi RPC started");
      class EchoObserver implements StreamObserver<EchoRequest> {
        private static final int delayMs = 200;
        private final List<Future<?>> echos = new ArrayList<>();

        @Override
        public void onNext(EchoRequest request) {
          System.out.println("Bidi RPC received request: " + request.getMessage());
          EchoResponse response
              = EchoResponse.newBuilder().setMessage(request.getMessage()).build();
          Runnable echo = () -> responseObserver.onNext(response);
          echos.add(scheduler.scheduleAtFixedRate(echo, delayMs, delayMs, TimeUnit.MILLISECONDS));
        }

        @Override
        public void onCompleted() {
          System.out.println("Bidi RPC client finished");
          // Let each echo happen two more times, and then stop.
          List<Future<?>> echosCopy = new ArrayList<>(echos);
          Runnable complete = () -> {
            stopEchos(echosCopy);
            responseObserver.onCompleted();
            System.out.println("Bidi RPC completed");
          };
          echos.add(scheduler.schedule(complete, 2*delayMs, TimeUnit.MILLISECONDS));
        }

        @Override
        public void onError(Throwable t) {
          System.out.println("Bidi RPC failed: " + Status.fromThrowable(t));
          stopEchos(echos);
          scheduler.execute(() -> responseObserver.onError(t));
        }

        public void onCancel() {
          // If onCompleted() hasn't been called by this point, then this method and onError are
          // both called. If onCompleted() has been called, then just this method is called.
          System.out.println("Bidi RPC cancelled");
          stopEchos(echos);
        }

        private void stopEchos(List<Future<?>> echos) {
          for (Future<?> echo : echos) {
            echo.cancel(false);
          }
        }
      }

      EchoObserver requestObserver = new EchoObserver();
      // onCancel() can be called even after the service completes or fails the RPC, because
      // callbacks are racy and the response still has to be sent to the client. Use
      // setOnCloseHandler() to be notified when the RPC completed without cancellation (as best as
      // the server is able to tell).
      responseCallObserver.setOnCancelHandler(requestObserver::onCancel);
      return requestObserver;
    }

    /**
     * Echos the request after a delay. It processes the request in-line within the callback. It
     * uses Context to be notified of RPC cancellation.
     */
    @Override
    public void unaryEcho(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
      // ServerCallStreamObserver.setOnCancelHandler(Runnable) is not useful for this method, since
      // this method only returns once it has a result. ServerCallStreamObserver guarantees the
      // Runnable is not run at the same time as other RPC callback methods (including this method),
      // so the cancellation notification would be guaranteed to occur too late.
      System.out.println("\nUnary RPC started: " + request.getMessage());
      Context currentContext = Context.current();
      // Let's start a multi-part operation. We can check cancellation periodically.
      for (int i = 0; i < 10; i++) {
        // ServerCallStreamObserver.isCancelled() returns true only if the RPC is cancelled.
        // Context.isCancelled() is similar, but also returns true when the RPC completes normally.
        // It doesn't matter which API is used here.
        if (currentContext.isCancelled()) {
          System.out.println("Unary RPC cancelled");
          responseObserver.onError(
              Status.CANCELLED.withDescription("RPC cancelled").asRuntimeException());
          return;
        }

        FutureTask<Void> task = new FutureTask<>(() -> {
          Thread.sleep(100); // Do some work
          return null;
        });
        // Some Java blocking APIs have a method to cancel an ongoing operation, like closing an
        // InputStream or interrupting the thread. We can use a Context listener to call that API
        // from another thread if the RPC is cancelled.
        Context.CancellationListener listener = (Context context) -> task.cancel(true);
        Context.current().addListener(listener, MoreExecutors.directExecutor());
        task.run(); // A cancellable operation
        Context.current().removeListener(listener);

        // gRPC stubs observe io.grpc.Context cancellation, so cancellation is automatically
        // propagated when performing an RPC. You can use a different Context or use Context.fork()
        // to disable the automatic propagation. For example,
        //   Context.ROOT.call(() -> futureStub.unaryEcho(request));
        //   context.fork().call(() -> futureStub.unaryEcho(request));
      }
      responseObserver.onNext(
          EchoResponse.newBuilder().setMessage(request.getMessage()).build());
      responseObserver.onCompleted();
      System.out.println("Unary RPC completed");
    }
  }
}
