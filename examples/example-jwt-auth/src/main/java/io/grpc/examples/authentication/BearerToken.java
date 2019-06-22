package io.grpc.examples.authentication;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.concurrent.Executor;

/**
 * CallCredentials implementation, which carries the JWT value that will be propagated to the server
 * in the request metadata with the "Authorization" key and the "Bearer" prefix
 */
public class BearerToken extends CallCredentials {

  private String value;

  BearerToken(String value) {
    this.value = value;
  }

  @Override
  public void applyRequestMetadata(final RequestInfo requestInfo, final Executor executor,
      final MetadataApplier metadataApplier) {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          Metadata headers = new Metadata();
          headers.put(Constant.AUTHORIZATION_METADATA_KEY,
              String.format("%s %s", Constant.BEARER_TYPE, value));
          metadataApplier.apply(headers);
        } catch (Throwable e) {
          metadataApplier.fail(Status.UNAUTHENTICATED.withCause(e));
        }
      }
    });
  }

  @Override
  public void thisUsesUnstableApi() {
    // noop
  }
}
