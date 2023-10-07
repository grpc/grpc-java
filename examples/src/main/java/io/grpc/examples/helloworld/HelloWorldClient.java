package io.grpc.examples.helloworld;
import io.grpc.Channel;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;


public class HelloWorldClient {
  private static final Logger logger = Logger.getLogger(HelloWorldClient.class.getName());

  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  public HelloWorldClient(Channel channel) {
  
    blockingStub = GreeterGrpc.newBlockingStub(channel);
  }

  public void greet(String name, String language, boolean streaming) {
    if (!streaming) {
      logger.info("Will try to greet " + name + " in " + language + "...");
      
      HelloRequest request = HelloRequest.newBuilder()
          .setName(name)
          .setLanguage(language)
          .build();
      
      HelloReply response;
      try {
        response = blockingStub.sayHello(request);
      } catch (StatusRuntimeException e) {
        logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
        return;
      }
      
      logger.info("Greeting: " + response.getMessage());
    } else {
      logger.info("Will try to greet " + name + " in " + language + "in streaming");
      
      HelloRequest request = HelloRequest.newBuilder()
          .setName(name)
          .setLanguage(language)
          .build();
      
      HelloReply response;
    try {
                blockingStub.sayHelloStream(request)
                        .forEachRemaining(reply -> logger.info("Greeting: " + reply.getMessage()));
            } catch (StatusRuntimeException e) {
                logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
                return;
            }
      
      
    }
  }
  public static void main(String[] args) {
    String user = "world";
    String language = "English";
    String target = "localhost:50051";
    boolean type = false;
    if (args.length > 0) {
        if ("--help".equals(args[0])) {
            System.err.println("Usage: [name [language [target]]]");
            System.err.println("");
            System.err.println("  name      The name you wish to be greeted by. Defaults to " + user);
            System.err.println("  language  The language to use for the greeting. Defaults to " + language);
            System.err.println("  target    The server to connect to. Defaults to " + target);
            System.exit(1);
        }
        
        String[] params = args[0].split(" ");
        if (params.length > 0) {
            user = params[0];
        }
        if (params.length > 1) {
            language = params[1];
        }
        if (params.length > 2) {
            type = Boolean.parseBoolean(params[2]);
        }
    }

    if (args.length > 1) {
        target = args[1];
    }

    ManagedChannel channel = null;
    try {
        channel = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create()).build();
        HelloWorldClient client = new HelloWorldClient(channel);
        client.greet(user, language, type);
    } catch (Exception e) {
        System.err.println("Exception occurred: " + e.getMessage());
    } finally {
        if (channel != null) {
            try {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}


  
}
