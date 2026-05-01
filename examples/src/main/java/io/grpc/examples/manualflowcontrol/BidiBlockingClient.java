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

package io.grpc.examples.manualflowcontrol;

import com.google.protobuf.ByteString;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusException;
import io.grpc.examples.manualflowcontrol.StreamingGreeterGrpc.StreamingGreeterBlockingV2Stub;
import io.grpc.stub.BlockingClientCall;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;


/**
 * A class that tries multiple ways to do blocking bidi streaming
 * communication with an echo server
 */
public class BidiBlockingClient {

  private static final Logger logger = Logger.getLogger(BidiBlockingClient.class.getName());

  /**
   * Greet server. If provided, the first element of {@code args} is the name to use in the
   * greeting. The second argument is the target server. You can see the multiplexing in the server
   * logs.
   */
  public static void main(String[] args) throws Exception {
    System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tH:%1$tM:%1$tS %5$s%6$s%n");

    // Access a service running on the local machine on port 50051
    String target = "localhost:50051";
    // Allow passing in the user and target strings as command line arguments
    if (args.length > 0) {
      if ("--help".equals(args[0])) {
        System.err.println("Usage: [target]\n");
        System.err.println("  target  The server to connect to. Defaults to " + target);
        System.exit(1);
      }
      target = args[0];
    }

    // Create a communication channel to the server, known as a Channel. Channels are thread-safe
    // and reusable. It is common to create channels at the beginning of your application and reuse
    // them until the application shuts down.
    //
    // For the example we use plaintext insecure credentials to avoid needing TLS certificates. To
    // use TLS, use TlsChannelCredentials instead.
    ManagedChannel channel = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())
        .build();
    StreamingGreeterBlockingV2Stub blockingStub = StreamingGreeterGrpc.newBlockingV2Stub(channel);
    List<String> echoInput = names();
    try {
      long start = System.currentTimeMillis();
      List<String> twoThreadResult = useTwoThreads(blockingStub, echoInput);
      long finish = System.currentTimeMillis();

      System.out.println("The echo requests and results were:");
      printResultMessage("Input", echoInput, 0L);
      printResultMessage("2 threads", twoThreadResult, finish - start);
    } finally {
      // ManagedChannels use resources like threads and TCP connections. To prevent leaking these
      // resources the channel should be shut down when it will no longer be used. If it may be used
      // again leave it running.
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static void printResultMessage(String type, List<String> result, long millis) {
    String msg = String.format("%-32s: %2d, %.3f sec", type, result.size(), millis/1000.0);
    logger.info(msg);
  }

  private static void logMethodStart(String method) {
    logger.info("--------------------- Starting to process using method:  " + method);
  }

  /**
   *  Create 2 threads, one that writes all values, and one that reads until the stream closes.
   */
  private static List<String> useTwoThreads(StreamingGreeterBlockingV2Stub blockingStub,
      List<String> valuesToWrite) throws InterruptedException {
    logMethodStart("Two Threads");

    List<String> readValues = new ArrayList<>();
    final BlockingClientCall<HelloRequest, HelloReply> stream = blockingStub.sayHelloStreaming();

    Thread reader = new Thread(null,
        new Runnable() {
          @Override
          public void run() {
            int count = 0;
            try {
              while (stream.hasNext()) {
                readValues.add(stream.read().getMessage());
                if (++count % 10 == 0) {
                  logger.info("Finished " + count + " reads");
                }
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              stream.cancel("Interrupted", e);
            } catch (StatusException e) {
              logger.warning("Encountered error while reading: " + e);
            }
          }
        },"reader");

    Thread writer = new Thread(null,
        new Runnable() {
      @Override
      public void run() {
        ByteString padding = createPadding();
        int count = 0;
        Iterator<String> iterator = valuesToWrite.iterator();
        boolean hadProblem = false;
        try {
          while (iterator.hasNext()) {
            if (!stream.write(HelloRequest.newBuilder().setName(iterator.next()).setPadding(padding)
                .build())) {
              logger.warning("Stream closed before writes completed");
              hadProblem = true;
              break;
            }
            if (++count % 10 == 0) {
              logger.info("Finished " + count + " writes");
            }
          }
          if (!hadProblem) {
            logger.info("Completed writes");
            stream.halfClose();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          stream.cancel("Interrupted", e);
        } catch (StatusException e) {
          logger.warning("Encountered error while writing: " + e);
        }
      }
    }, "writer");

    writer.start();
    reader.start();
    writer.join();
    reader.join();

    return readValues;
  }

  private static ByteString createPadding() {
    int multiple = 50;
    ByteBuffer data = ByteBuffer.allocate(1024 * multiple);

    for (int i = 0; i < multiple * 1024 / 4; i++) {
      data.putInt(4 * i, 1111);
    }

    return ByteString.copyFrom(data);
  }


  private static List<String> names() {
    return Arrays.asList(
        "Sophia",
        "Jackson",
        "Emma",
        "Aiden",
        "Olivia",
        "Lucas",
        "Ava",
        "Liam",
        "Mia",
        "Noah",
        "Isabella",
        "Ethan",
        "Riley",
        "Mason",
        "Aria",
        "Caden",
        "Zoe",
        "Oliver",
        "Charlotte",
        "Elijah",
        "Lily",
        "Grayson",
        "Layla",
        "Jacob",
        "Amelia",
        "Michael",
        "Emily",
        "Benjamin",
        "Madelyn",
        "Carter",
        "Aubrey",
        "James",
        "Adalyn",
        "Jayden",
        "Madison",
        "Logan",
        "Chloe",
        "Alexander",
        "Harper",
        "Caleb",
        "Abigail",
        "Ryan",
        "Aaliyah",
        "Luke",
        "Avery",
        "Daniel",
        "Evelyn",
        "Jack",
        "Kaylee",
        "William",
        "Ella",
        "Owen",
        "Ellie",
        "Gabriel",
        "Scarlett",
        "Matthew",
        "Arianna",
        "Connor",
        "Hailey",
        "Jayce",
        "Nora",
        "Isaac",
        "Addison",
        "Sebastian",
        "Brooklyn",
        "Henry",
        "Hannah",
        "Muhammad",
        "Mila",
        "Cameron",
        "Leah",
        "Wyatt",
        "Elizabeth",
        "Dylan",
        "Sarah",
        "Nathan",
        "Eliana",
        "Nicholas",
        "Mackenzie",
        "Julian",
        "Peyton",
        "Eli",
        "Maria",
        "Levi",
        "Grace",
        "Isaiah",
        "Adeline",
        "Landon",
        "Elena",
        "David",
        "Anna",
        "Christian",
        "Victoria",
        "Andrew",
        "Camilla",
        "Brayden",
        "Lillian",
        "John",
        "Natalie",
        "Lincoln"
    );
  }
}
