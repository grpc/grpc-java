/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.stub;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Utility functions for binding and receiving headers.
 */
public final class MetadataUtils {
  // Prevent instantiation
  private MetadataUtils() {}

  /**
   * Attaches a set of request headers to a stub.
   *
   * @param stub to bind the headers to.
   * @param extraHeaders the headers to be passed by each call on the returned stub.
   * @return an implementation of the stub with {@code extraHeaders} bound to each call.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1789")
  public static <T extends AbstractStub<T>> T attachHeaders(
      T stub,
      final Metadata extraHeaders) {
    return stub.withInterceptors(newAttachHeadersInterceptor(extraHeaders));
  }

  /**
   * Returns a client interceptor that attaches a set of headers to requests.
   *
   * @param extraHeaders the headers to be passed by each call that is processed by the returned
   *                     interceptor
   */
  public static ClientInterceptor newAttachHeadersInterceptor(final Metadata extraHeaders) {
    return new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method,
          CallOptions callOptions,
          Channel next) {
        return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
          @Override
          public void start(Listener<RespT> responseListener, Metadata headers) {
            headers.merge(extraHeaders);
            super.start(responseListener, headers);
          }
        };
      }
    };
  }

  /**
   * Captures the last received metadata for a stub. Useful for testing
   *
   * @param stub to capture for
   * @param headersCapture to record the last received headers
   * @param trailersCapture to record the last received trailers
   * @return an implementation of the stub that allows to access the last received call's
   *         headers and trailers via {@code headersCapture} and {@code trailersCapture}.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1789")
  public static <T extends AbstractStub<T>> T captureMetadata(
      T stub,
      AtomicReference<Metadata> headersCapture,
      AtomicReference<Metadata> trailersCapture) {
    return stub.withInterceptors(
        newCaptureMetadataInterceptor(headersCapture, trailersCapture));
  }

  /**
   * Captures the last received metadata on a channel. Useful for testing.
   *
   * @param headersCapture to record the last received headers
   * @param trailersCapture to record the last received trailers
   * @return an implementation of the channel with captures installed.
   */
  public static ClientInterceptor newCaptureMetadataInterceptor(
      final AtomicReference<Metadata> headersCapture,
      final AtomicReference<Metadata> trailersCapture) {
    return new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method,
          CallOptions callOptions,
          Channel next) {
        return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
          @Override
          public void start(Listener<RespT> responseListener, Metadata headers) {
            headersCapture.set(null);
            trailersCapture.set(null);
            super.start(new SimpleForwardingClientCallListener<RespT>(responseListener) {
              @Override
              public void onHeaders(Metadata headers) {
                headersCapture.set(headers);
                super.onHeaders(headers);
              }

              @Override
              public void onClose(Status status, Metadata trailers) {
                trailersCapture.set(trailers);
                super.onClose(status, trailers);
              }
            }, headers);
          }
        };
      }
    };
  }
}
