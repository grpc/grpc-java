package io.grpc.inprocess;

import com.google.common.util.concurrent.SettableFuture;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StringMarshaller;
import io.grpc.internal.ManagedChannelImpl;
import io.grpc.internal.ServerImpl;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;

@RunWith(JUnit4.class)
public class InProcessChannelTest {
  @Test public void anonymousChannel() throws Exception {
    // Create simple server with one endpoint
    MethodDescriptor<String, String> unaryMethod =
        MethodDescriptor.create(MethodType.UNARY, "FooService/Unary",
            new StringMarshaller(), new StringMarshaller());
    ServerImpl server = InProcessServerBuilder.forName("doesn't matter")
        .addService(
            ServerServiceDefinition.builder("FooService").addMethod(unaryMethod,
                new ServerCallHandler<String, String>() {
                  // Call handler simply reverses the request string
                  @Override
                  public Listener<String> startCall(MethodDescriptor<String, String> method,
                      final ServerCall<String> call, Metadata headers) {
                    call.request(2);
                    return new Listener<String>() {
                      @Override public void onMessage(String message) {
                        call.sendHeaders(new Metadata());
                        StringBuilder sb = new StringBuilder(message);
                        sb.reverse();
                        call.sendMessage(sb.toString());
                        call.close(Status.OK, new Metadata());
                      }
                    };
                  }
                })
            .build())
        .build();

    // Anonymous in-process channel to the server
    ManagedChannelImpl channel = InProcessChannelBuilder.anonymousChannelTo(server).build();

    // Try out a call
    try {
      ClientCall<String, String> call = channel.newCall(unaryMethod, CallOptions.DEFAULT);
      final SettableFuture<String> result = SettableFuture.create();
      call.start(new ClientCall.Listener<String>() {
        private String response;

        @Override public void onMessage(String message) {
          this.response = message;
        }

        @Override public void onClose(Status status, Metadata trailers) {
          if (status.getCode() == Code.OK) {
            result.set(response);
          } else {
            result.setException(status.asException());
          }
        }
      }, new Metadata());
      call.sendMessage("abcdefg");
      call.halfClose();
      call.request(2);

      String response = result.get();
      assertEquals("gfedcba", response);

    } finally {
      channel.shutdown();
      server.shutdown();
    }
  }
}
