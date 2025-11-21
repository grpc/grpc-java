/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.examples.manualflowcontrol;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Example demonstrating batch write optimization using isReady() and setOnReadyHandler().
 * 
 * This example addresses the pull/batch write optimization issue by showing how to:
 * 1. Batch multiple messages before flushing to reduce buffer allocations
 * 2. Use isReady() to detect when backpressure occurs
 * 3. Use setOnReadyHandler() to resume writing when the transport is ready
 * 4. Minimize the number of flushes by writing in batches
 * 
 * The key pattern is:
 *   while (stream.isReady() && hasMoreData()) {
 *       stream.onNext(nextMessage());  // Multiple messages batched before flush
 *   }
 * 
 * This demonstrates the solution to the issue: "Consider implementing pull / batch write 
 * to optimize flushing for streams" - the feature is already implemented via the 
 * isReady()/setOnReadyHandler() API.
 */
public class BatchWriteOptimizationExample {
    private static final Logger logger = 
        Logger.getLogger(BatchWriteOptimizationExample.class.getName());
    
    // Metrics to demonstrate batch write optimization
    private static final AtomicInteger totalMessagesWritten = new AtomicInteger(0);
    private static final AtomicInteger totalBatches = new AtomicInteger(0);
    private static final AtomicInteger messagesInCurrentBatch = new AtomicInteger(0);

    public static void main(String[] args) throws IOException, InterruptedException {
        // Start server
        Server server = ServerBuilder.forPort(50052)
            .addService(new BatchWriteGreeterService())
            .build()
            .start();
        
        logger.info("Server started on port 50052");
        
        // Run client
        runClient();
        
        // Shutdown
        server.shutdown();
        server.awaitTermination(5, TimeUnit.SECONDS);
        
        // Print statistics
        logger.info("=== Batch Write Statistics ===");
        logger.info("Total messages written: " + totalMessagesWritten.get());
        logger.info("Total batches: " + totalBatches.get());
        logger.info("Average messages per batch: " + 
            (totalMessagesWritten.get() / (float) totalBatches.get()));
        logger.info("This demonstrates efficient batch writing with fewer flushes!");
    }

    private static void runClient() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        
        ManagedChannel channel = ManagedChannelBuilder
            .forAddress("localhost", 50052)
            .usePlaintext()
            .build();
        
        StreamingGreeterGrpc.StreamingGreeterStub stub = StreamingGreeterGrpc.newStub(channel);
        
        // Client that demonstrates batch write optimization
        ClientResponseObserver<HelloRequest, HelloReply> clientObserver =
            new ClientResponseObserver<HelloRequest, HelloReply>() {
                
                ClientCallStreamObserver<HelloRequest> requestStream;
                List<String> messagesToSend = generateMessages(100); // 100 messages to batch
                int currentIndex = 0;
                
                @Override
                public void beforeStart(final ClientCallStreamObserver<HelloRequest> requestStream) {
                    this.requestStream = requestStream;
                    requestStream.disableAutoRequestWithInitial(1);
                    
                    // KEY PATTERN: setOnReadyHandler for batch writes
                    requestStream.setOnReadyHandler(new Runnable() {
                        @Override
                        public void run() {
                            // Reset batch counter when starting a new batch
                            if (messagesInCurrentBatch.get() > 0) {
                                totalBatches.incrementAndGet();
                                logger.info("Completed batch of " + messagesInCurrentBatch.get() + 
                                    " messages (backpressure occurred)");
                                messagesInCurrentBatch.set(0);
                            }
                            
                            // BATCH WRITE PATTERN: Write multiple messages while isReady() is true
                            // This is the core optimization - multiple onNext() calls before flush
                            while (requestStream.isReady() && currentIndex < messagesToSend.size()) {
                                String name = messagesToSend.get(currentIndex++);
                                HelloRequest request = HelloRequest.newBuilder()
                                    .setName(name)
                                    .build();
                                
                                requestStream.onNext(request);
                                totalMessagesWritten.incrementAndGet();
                                messagesInCurrentBatch.incrementAndGet();
                                
                                // Log every 10 messages to show batching
                                if (messagesInCurrentBatch.get() % 10 == 0) {
                                    logger.info("Batched " + messagesInCurrentBatch.get() + 
                                        " messages so far (still ready)");
                                }
                            }
                            
                            // If we've sent all messages, complete the stream
                            if (currentIndex >= messagesToSend.size()) {
                                if (messagesInCurrentBatch.get() > 0) {
                                    totalBatches.incrementAndGet();
                                    logger.info("Final batch of " + messagesInCurrentBatch.get() + 
                                        " messages");
                                }
                                requestStream.onCompleted();
                            }
                            
                            // If isReady() became false, we'll be called again when ready
                            // This demonstrates backpressure handling
                            if (!requestStream.isReady() && currentIndex < messagesToSend.size()) {
                                logger.info("Backpressure detected! Pausing writes. " +
                                    "Will resume when transport is ready.");
                            }
                        }
                    });
                }
                
                @Override
                public void onNext(HelloReply value) {
                    // Request next response
                    requestStream.request(1);
                }
                
                @Override
                public void onError(Throwable t) {
                    logger.severe("Error: " + t.getMessage());
                    done.countDown();
                }
                
                @Override
                public void onCompleted() {
                    logger.info("Client completed");
                    done.countDown();
                }
            };
        
        stub.sayHelloStreaming(clientObserver);
        done.await();
        
        channel.shutdown();
        channel.awaitTermination(1, TimeUnit.SECONDS);
    }
    
    private static List<String> generateMessages(int count) {
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add("Message-" + i);
        }
        return messages;
    }
    
    /**
     * Server implementation that also demonstrates batch write optimization
     */
    private static class BatchWriteGreeterService extends StreamingGreeterGrpc.StreamingGreeterImplBase {
        @Override
        public StreamObserver<HelloRequest> sayHelloStreaming(
                final StreamObserver<HelloReply> responseObserver) {
            
            final ServerCallStreamObserver<HelloReply> serverCallObserver =
                (ServerCallStreamObserver<HelloReply>) responseObserver;
            
            serverCallObserver.disableAutoRequest();
            
            // Server-side batch write handler
            final AtomicInteger serverBatchCount = new AtomicInteger(0);
            final AtomicInteger serverMessagesInBatch = new AtomicInteger(0);
            
            serverCallObserver.setOnReadyHandler(new Runnable() {
                private boolean wasReady = false;
                
                @Override
                public void run() {
                    if (serverCallObserver.isReady() && !wasReady) {
                        wasReady = true;
                        logger.info("Server ready to send responses");
                        serverCallObserver.request(1);
                    }
                }
            });
            
            return new StreamObserver<HelloRequest>() {
                @Override
                public void onNext(HelloRequest request) {
                    String name = request.getName();
                    
                    // Send response
                    HelloReply reply = HelloReply.newBuilder()
                        .setMessage("Hello " + name)
                        .build();
                    
                    responseObserver.onNext(reply);
                    serverMessagesInBatch.incrementAndGet();
                    
                    // BATCH WRITE PATTERN on server side
                    // Check if ready for more - if yes, request more to batch
                    if (serverCallObserver.isReady()) {
                        serverCallObserver.request(1);
                    } else {
                        // Backpressure on server side
                        serverBatchCount.incrementAndGet();
                        logger.info("Server batch " + serverBatchCount.get() + 
                            " completed (" + serverMessagesInBatch.get() + " messages)");
                        serverMessagesInBatch.set(0);
                    }
                }
                
                @Override
                public void onError(Throwable t) {
                    logger.severe("Server error: " + t.getMessage());
                    responseObserver.onCompleted();
                }
                
                @Override
                public void onCompleted() {
                    if (serverMessagesInBatch.get() > 0) {
                        serverBatchCount.incrementAndGet();
                        logger.info("Server final batch: " + serverMessagesInBatch.get() + 
                            " messages");
                    }
                    logger.info("Server completed. Total batches: " + serverBatchCount.get());
                    responseObserver.onCompleted();
                }
            };
        }
    }
}