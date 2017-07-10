/*
 * Copyright 2017, Google Inc. All rights reserved.
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
package io.grpc.testing.integration.gapic;

import com.google.api.core.BetaApi;
import com.google.api.gax.grpc.ChannelAndExecutor;
import com.google.api.gax.grpc.ClientContext;
import com.google.api.gax.grpc.StreamingCallable;
import com.google.api.gax.grpc.UnaryCallable;
import com.google.api.pathtemplate.PathTemplate;
import com.google.auth.Credentials;
import com.google.protobuf.EmptyProtos.Empty;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import io.grpc.testing.integration.Messages.StreamingInputCallRequest;
import io.grpc.testing.integration.Messages.StreamingInputCallResponse;
import io.grpc.testing.integration.Messages.StreamingOutputCallRequest;
import io.grpc.testing.integration.Messages.StreamingOutputCallResponse;
import io.grpc.ManagedChannel;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Generated;

// AUTO-GENERATED DOCUMENTATION AND SERVICE
/**
 * Service Description: A simple service to test the various types of RPCs and experiment with
 * performance with various types of payload.
 *
 * <p>This class provides the ability to make remote calls to the backing service through method
 * calls that map to API methods. Sample code to get started:
 *
 * <pre>
 * <code>
 * try (TestServiceClient testServiceClient = TestServiceClient.create()) {
 *   Empty request = Empty.newBuilder().build();
 *   Empty response = testServiceClient.emptyCall(request);
 * }
 * </code>
 * </pre>
 *
 * <p>Note: close() needs to be called on the testServiceClient object to clean up resources such
 * as threads. In the example above, try-with-resources is used, which automatically calls
 * close().
 *
 * <p>The surface of this class includes several types of Java methods for each of the API's methods:
 *
 * <ol>
 * <li> A "flattened" method. With this type of method, the fields of the request type have been
 * converted into function parameters. It may be the case that not all fields are available
 * as parameters, and not every API method will have a flattened method entry point.
 * <li> A "request object" method. This type of method only takes one parameter, a request
 * object, which must be constructed before the call. Not every API method will have a request
 * object method.
 * <li> A "callable" method. This type of method takes no parameters and returns an immutable
 * API callable object, which can be used to initiate calls to the service.
 * </ol>
 *
 * <p>See the individual methods for example code.
 *
 * <p>Many parameters require resource names to be formatted in a particular way. To assist
 * with these names, this class includes a format method for each type of name, and additionally
 * a parse method to extract the individual identifiers contained within names that are
 * returned.
 *
 * <p>This class can be customized by passing in a custom instance of TestServiceSettings to
 * create(). For example:
 *
 * <pre>
 * <code>
 * TestServiceSettings testServiceSettings =
 *     TestServiceSettings.defaultBuilder()
 *         .setCredentialsProvider(FixedCredentialsProvider.create(myCredentials))
 *         .build();
 * TestServiceClient testServiceClient =
 *     TestServiceClient.create(testServiceSettings);
 * </code>
 * </pre>
 */
@Generated("by GAPIC")
public class TestServiceClient implements AutoCloseable {
  private final TestServiceSettings settings;
  private final ScheduledExecutorService executor;
  private final ManagedChannel channel;
  private final List<AutoCloseable> closeables = new ArrayList<>();

  private final UnaryCallable<Empty, Empty> emptyCallCallable;
  private final UnaryCallable<SimpleRequest, SimpleResponse> unaryCallCallable;
  private final StreamingCallable<StreamingOutputCallRequest, StreamingOutputCallResponse> streamingOutputCallCallable;
  private final StreamingCallable<StreamingInputCallRequest, StreamingInputCallResponse> streamingInputCallCallable;
  private final StreamingCallable<StreamingOutputCallRequest, StreamingOutputCallResponse> fullDuplexCallCallable;
  private final StreamingCallable<StreamingOutputCallRequest, StreamingOutputCallResponse> halfDuplexCallCallable;



  /**
   * Constructs an instance of TestServiceClient with default settings.
   */
  public static final TestServiceClient create() throws IOException {
    return create(TestServiceSettings.defaultBuilder().build());
  }

  /**
   * Constructs an instance of TestServiceClient, using the given settings.
   * The channels are created based on the settings passed in, or defaults for any
   * settings that are not set.
   */
  public static final TestServiceClient create(TestServiceSettings settings) throws IOException {
    return new TestServiceClient(settings);
  }

  /**
   * Constructs an instance of TestServiceClient, using the given settings.
   * This is protected so that it easy to make a subclass, but otherwise, the static
   * factory methods should be preferred.
   */
  protected TestServiceClient(TestServiceSettings settings) throws IOException {
    this.settings = settings;
    ChannelAndExecutor channelAndExecutor = settings.getChannelAndExecutor();
    this.executor = channelAndExecutor.getExecutor();
    this.channel = channelAndExecutor.getChannel();
    Credentials credentials = settings.getCredentialsProvider().getCredentials();

    ClientContext clientContext =
        ClientContext.newBuilder()
            .setExecutor(this.executor)
            .setChannel(this.channel)
            .setCredentials(credentials)
            .build();


    this.emptyCallCallable = UnaryCallable.create(settings.emptyCallSettings(), clientContext);
    this.unaryCallCallable = UnaryCallable.create(settings.unaryCallSettings(), clientContext);
    this.streamingOutputCallCallable = StreamingCallable.create(settings.streamingOutputCallSettings(), clientContext);
    this.streamingInputCallCallable = StreamingCallable.create(settings.streamingInputCallSettings(), clientContext);
    this.fullDuplexCallCallable = StreamingCallable.create(settings.fullDuplexCallSettings(), clientContext);
    this.halfDuplexCallCallable = StreamingCallable.create(settings.halfDuplexCallSettings(), clientContext);

    if (settings.getChannelProvider().shouldAutoClose()) {
      closeables.add(
        new Closeable() {
          @Override
          public void close() throws IOException {
            channel.shutdown();
          }
        });
    }
    if (settings.getExecutorProvider().shouldAutoClose()) {
      closeables.add(
        new Closeable() {
          @Override
          public void close() throws IOException {
            executor.shutdown();
          }
        });
    }
  }

  public final TestServiceSettings getSettings() {
    return settings;
  }


  // AUTO-GENERATED DOCUMENTATION AND METHOD
  /**
   * One empty request followed by one empty response.
   *
   * Sample code:
   * <pre><code>
   * try (TestServiceClient testServiceClient = TestServiceClient.create()) {
   *   Empty request = Empty.newBuilder().build();
   *   Empty response = testServiceClient.emptyCall(request);
   * }
   * </code></pre>
   *
   * @param request The request object containing all of the parameters for the API call.
   * @throws com.google.api.gax.grpc.ApiException if the remote call fails
   */
  private final Empty emptyCall(Empty request) {
    return emptyCallCallable().call(request);
  }

  // AUTO-GENERATED DOCUMENTATION AND METHOD
  /**
   * One empty request followed by one empty response.
   *
   * Sample code:
   * <pre><code>
   * try (TestServiceClient testServiceClient = TestServiceClient.create()) {
   *   Empty request = Empty.newBuilder().build();
   *   ApiFuture&lt;Empty&gt; future = testServiceClient.emptyCallCallable().futureCall(request);
   *   // Do something
   *   Empty response = future.get();
   * }
   * </code></pre>
   */
  public final UnaryCallable<Empty, Empty> emptyCallCallable() {
    return emptyCallCallable;
  }

  // AUTO-GENERATED DOCUMENTATION AND METHOD
  /**
   * One request followed by one response.
   * The server returns the client payload as-is.
   *
   * Sample code:
   * <pre><code>
   * try (TestServiceClient testServiceClient = TestServiceClient.create()) {
   *   SimpleRequest request = SimpleRequest.newBuilder().build();
   *   SimpleResponse response = testServiceClient.unaryCall(request);
   * }
   * </code></pre>
   *
   * @param request The request object containing all of the parameters for the API call.
   * @throws com.google.api.gax.grpc.ApiException if the remote call fails
   */
  private final SimpleResponse unaryCall(SimpleRequest request) {
    return unaryCallCallable().call(request);
  }

  // AUTO-GENERATED DOCUMENTATION AND METHOD
  /**
   * One request followed by one response.
   * The server returns the client payload as-is.
   *
   * Sample code:
   * <pre><code>
   * try (TestServiceClient testServiceClient = TestServiceClient.create()) {
   *   SimpleRequest request = SimpleRequest.newBuilder().build();
   *   ApiFuture&lt;SimpleResponse&gt; future = testServiceClient.unaryCallCallable().futureCall(request);
   *   // Do something
   *   SimpleResponse response = future.get();
   * }
   * </code></pre>
   */
  public final UnaryCallable<SimpleRequest, SimpleResponse> unaryCallCallable() {
    return unaryCallCallable;
  }

  // AUTO-GENERATED DOCUMENTATION AND METHOD
  /**
   * One request followed by a sequence of responses (streamed download).
   * The server returns the payload with client desired type and sizes.
   *
   * Sample code:
   * <pre><code>
   * try (TestServiceClient testServiceClient = TestServiceClient.create()) {
   *   ApiStreamObserver&lt;StreamingOutputCallResponse&gt; responseObserver =
   *       new ApiStreamObserver&lt;StreamingOutputCallResponse&gt;() {
   *         {@literal @}Override
   *         public void onNext(StreamingOutputCallResponse response) {
   *           // Do something when receive a response
   *         }
   *
   *         {@literal @}Override
   *         public void onError(Throwable t) {
   *           // Add error-handling
   *         }
   *
   *         {@literal @}Override
   *         public void onCompleted() {
   *           // Do something when complete.
   *         }
   *       };
   *
   *   StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder().build();
   *
   *   testServiceClient.streamingOutputCallCallable().serverStreamingCall(request, responseObserver));
   * }
   * </code></pre>
   */
  public final StreamingCallable<StreamingOutputCallRequest, StreamingOutputCallResponse> streamingOutputCallCallable() {
    return streamingOutputCallCallable;
  }

  // AUTO-GENERATED DOCUMENTATION AND METHOD
  /**
   * A sequence of requests followed by one response (streamed upload).
   * The server returns the aggregated size of client payload as the result.
   *
   * Sample code:
   * <pre><code>
   * try (TestServiceClient testServiceClient = TestServiceClient.create()) {
   *   ApiStreamObserver&lt;StreamingInputCallResponse&gt; responseObserver =
   *       new ApiStreamObserver&lt;StreamingInputCallResponse&gt;() {
   *         {@literal @}Override
   *         public void onNext(StreamingInputCallResponse response) {
   *           // Do something when receive a response
   *         }
   *
   *         {@literal @}Override
   *         public void onError(Throwable t) {
   *           // Add error-handling
   *         }
   *
   *         {@literal @}Override
   *         public void onCompleted() {
   *           // Do something when complete.
   *         }
   *       };
   *   ApiStreamObserver&lt;StreamingRecognizeRequest&gt; requestObserver =
   *       testServiceClient.streamingInputCallCallable().clientStreamingCall(responseObserver));
   *
   *   StreamingInputCallRequest request = StreamingInputCallRequest.newBuilder().build();
   *   requestObserver.onNext(request);
   * }
   * </code></pre>
   */
  public final StreamingCallable<StreamingInputCallRequest, StreamingInputCallResponse> streamingInputCallCallable() {
    return streamingInputCallCallable;
  }

  // AUTO-GENERATED DOCUMENTATION AND METHOD
  /**
   * A sequence of requests with each request served by the server immediately.
   * As one request could lead to multiple responses, this interface
   * demonstrates the idea of full duplexing.
   *
   * Sample code:
   * <pre><code>
   * try (TestServiceClient testServiceClient = TestServiceClient.create()) {
   *   ApiStreamObserver&lt;StreamingOutputCallResponse&gt; responseObserver =
   *       new ApiStreamObserver&lt;StreamingOutputCallResponse&gt;() {
   *         {@literal @}Override
   *         public void onNext(StreamingOutputCallResponse response) {
   *           // Do something when receive a response
   *         }
   *
   *         {@literal @}Override
   *         public void onError(Throwable t) {
   *           // Add error-handling
   *         }
   *
   *         {@literal @}Override
   *         public void onCompleted() {
   *           // Do something when complete.
   *         }
   *       };
   *   ApiStreamObserver&lt;StreamingRecognizeRequest&gt; requestObserver =
   *       testServiceClient.fullDuplexCallCallable().bidiStreamingCall(responseObserver));
   *
   *   StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder().build();
   *   requestObserver.onNext(request);
   * }
   * </code></pre>
   */
  public final StreamingCallable<StreamingOutputCallRequest, StreamingOutputCallResponse> fullDuplexCallCallable() {
    return fullDuplexCallCallable;
  }

  // AUTO-GENERATED DOCUMENTATION AND METHOD
  /**
   * A sequence of requests followed by a sequence of responses.
   * The server buffers all the client requests and then serves them in order. A
   * stream of responses are returned to the client when the server starts with
   * first request.
   *
   * Sample code:
   * <pre><code>
   * try (TestServiceClient testServiceClient = TestServiceClient.create()) {
   *   ApiStreamObserver&lt;StreamingOutputCallResponse&gt; responseObserver =
   *       new ApiStreamObserver&lt;StreamingOutputCallResponse&gt;() {
   *         {@literal @}Override
   *         public void onNext(StreamingOutputCallResponse response) {
   *           // Do something when receive a response
   *         }
   *
   *         {@literal @}Override
   *         public void onError(Throwable t) {
   *           // Add error-handling
   *         }
   *
   *         {@literal @}Override
   *         public void onCompleted() {
   *           // Do something when complete.
   *         }
   *       };
   *   ApiStreamObserver&lt;StreamingRecognizeRequest&gt; requestObserver =
   *       testServiceClient.halfDuplexCallCallable().bidiStreamingCall(responseObserver));
   *
   *   StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder().build();
   *   requestObserver.onNext(request);
   * }
   * </code></pre>
   */
  public final StreamingCallable<StreamingOutputCallRequest, StreamingOutputCallResponse> halfDuplexCallCallable() {
    return halfDuplexCallCallable;
  }

  /**
   * Initiates an orderly shutdown in which preexisting calls continue but new calls are immediately
   * cancelled.
   */
  @Override
  public final void close() throws Exception {
    for (AutoCloseable closeable : closeables) {
      closeable.close();
    }
  }

}