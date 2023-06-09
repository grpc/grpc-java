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

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.manualflowcontrol.StreamingGreeterGrpc.StreamingGreeterBlockingStub;
import io.grpc.stub.BlockingBiDiStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;


/**
 * A class that tries multiple ways to do blocking bidi streaming
 * communication with an echo server
 */
public class BidiBlockingClient {

  private static final Logger logger = Logger.getLogger(BidiBlockingClient.class.getName());
  private static String lastLogMsg = "";
  private static int consecutiveCount = 0;

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
        System.err.println("Usage: [target]");
        System.err.println("");
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
    StreamingGreeterBlockingStub blockingStub = StreamingGreeterGrpc.newBlockingStub(channel);
    List<String> echoInput = names();
    try {
      long start = System.currentTimeMillis();
      List<String> simpleWrite = useSimpleWrite(blockingStub, echoInput);
      long t1 = System.currentTimeMillis();
      List<String> blockUntilSomethingReady = useBlockUntilSomethingReady(blockingStub, echoInput);
      long t2 = System.currentTimeMillis();

      System.out.println("The echo requests and results were:");
      printResultMessage("Input", echoInput, 0L);
      printResultMessage("simpleWrite", simpleWrite, t1 - start);
      printResultMessage("blockUntilSomethingReady", blockUntilSomethingReady, t2 - t1);
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
    lastLogMsg = "";
    consecutiveCount = 0;
  }

  private static void logAction(List<String> readValues, boolean lastWriteOk, String lastValue,
      int readCount) {
    String writeResult =
        (lastValue != null) ? (lastWriteOk ? "successful" : "not done") : "skipped";
    String msg = String.format("The write was %s.  There were %d values read",
        writeResult, readValues.size() - readCount);

    if (msg.equals(lastLogMsg)) {
      consecutiveCount++;
    } else {
      if (consecutiveCount > 0) {
        logger.info("Repeated " + consecutiveCount + " times");
      }
      consecutiveCount = 0;
      lastLogMsg = msg;
      logger.info(msg);
    }
  }

  private static List<String> useBlockUntilSomethingReady(
      StreamingGreeterBlockingStub stub, List<String> strings)
      throws InterruptedException, TimeoutException {

    logMethodStart("blockUntilSomethingReady");

    List<String> readValues = new ArrayList<>();
    BlockingBiDiStream<HelloRequest, HelloReply> stream = stub.sayHelloStreaming();
    Iterator<String> iterator = strings.iterator();

    TimeUnit readTimeUnit = TimeUnit.MILLISECONDS;

    while ((stream.getClosedStatus() == null)
        && (iterator.hasNext() || readValues.size() < strings.size())) {

      boolean doWrite;
      if (iterator.hasNext() && readValues.size() < strings.size()) {
        doWrite = stream.waitForReady(10, TimeUnit.MINUTES);
      } else {
        doWrite = iterator.hasNext();
      }

      if (doWrite) {
        HelloRequest req = HelloRequest.newBuilder().setName(iterator.next()).build();
        stream.write(req);
        if (!iterator.hasNext() &&
            (stream.getClosedStatus() == null || stream.getClosedStatus().isOk())) {
          stream.sendCloseWrite();
          readTimeUnit = TimeUnit.MINUTES; // No writes, so block longer on reads
          logger.info("Completed writes");
        }
      } else {
        HelloReply response = stream.read(10, readTimeUnit);
        if (response != null) {
          readValues.add(response.getMessage());
        }
      }
    }

    if (stream.getClosedStatus() != null && !stream.getClosedStatus().isOk()) {
      throw stream.getClosedStatus().asRuntimeException();
    }
    return readValues;
  }



















  /**
   *  Try to write all values (with breaks for reads as needed to manage flow control), and then
   *  read the rest.
   */
  private static List<String> useSimpleWrite(StreamingGreeterBlockingStub blockingStub,
      List<String> valuesToWrite) throws InterruptedException {
    logMethodStart("Simple Write");

    List<String> readValues = new ArrayList<>();
    BlockingBiDiStream<HelloRequest, HelloReply> stream = blockingStub.sayHelloStreaming();

    Thread reader = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          stream.waitForReady(10, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException e) {
         logger.info("Waiting exception was: " + e);
        }

        try {
          HelloReply response;
          while ((response = stream.read()) != null) {
            readValues.add(response.getMessage());
          }
        } catch (InterruptedException e) {
          stream.cancel("Interrupted", e);
        } catch (StatusRuntimeException e) {
          logger.warning("Encountered error while reading: " + e);
        }
      }
    });

    Thread writer = new Thread(new Runnable() {
      @Override
      public void run() {
        Iterator<String> iterator = valuesToWrite.iterator();
        boolean first = true;
        try {
          while (iterator.hasNext()) {
            stream.write(HelloRequest.newBuilder().setName(iterator.next()).build());
          }
          logger.info("Completed writes");
          stream.sendCloseWrite();
        } catch (InterruptedException e) {
          stream.cancel("Interrupted", e);
          Thread.currentThread().interrupt();
        }
      }
    });



    writer.start();
    reader.start();
    writer.join();
    reader.join();

    return readValues;
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
