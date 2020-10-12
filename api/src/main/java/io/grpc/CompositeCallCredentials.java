/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc;

import com.google.common.base.Preconditions;
import java.util.concurrent.Executor;

/**
 * Uses multiple {@code CallCredentials} as if they were one. If the first credential fails, the
 * second will not be used. Both must succeed to allow the RPC.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7479")
public final class CompositeCallCredentials extends CallCredentials {
  private final CallCredentials credentials1;
  private final CallCredentials credentials2;

  public CompositeCallCredentials(CallCredentials creds1, CallCredentials creds2) {
    this.credentials1 = Preconditions.checkNotNull(creds1, "creds1");
    this.credentials2 = Preconditions.checkNotNull(creds2, "creds2");
  }

  @Override
  public void applyRequestMetadata(
      RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
    credentials1.applyRequestMetadata(requestInfo, appExecutor,
        new WrappingMetadataApplier(requestInfo, appExecutor, applier, Context.current()));
  }

  @Override
  public void thisUsesUnstableApi() {}

  private final class WrappingMetadataApplier extends MetadataApplier {
    private final RequestInfo requestInfo;
    private final Executor appExecutor;
    private final MetadataApplier delegate;
    private final Context context;

    public WrappingMetadataApplier(
        RequestInfo requestInfo, Executor appExecutor, MetadataApplier delegate, Context context) {
      this.requestInfo = requestInfo;
      this.appExecutor = appExecutor;
      this.delegate = Preconditions.checkNotNull(delegate, "delegate");
      this.context = Preconditions.checkNotNull(context, "context");
    }

    @Override
    public void apply(Metadata headers) {
      Preconditions.checkNotNull(headers, "headers");
      Context previous = context.attach();
      try {
        credentials2.applyRequestMetadata(
            requestInfo, appExecutor, new CombiningMetadataApplier(delegate, headers));
      } finally {
        context.detach(previous);
      }
    }

    @Override
    public void fail(Status status) {
      delegate.fail(status);
    }
  }

  private static final class CombiningMetadataApplier extends MetadataApplier {
    private final MetadataApplier delegate;
    private final Metadata firstHeaders;

    public CombiningMetadataApplier(MetadataApplier delegate, Metadata firstHeaders) {
      this.delegate = delegate;
      this.firstHeaders = firstHeaders;
    }

    @Override
    public void apply(Metadata headers) {
      Preconditions.checkNotNull(headers, "headers");
      Metadata combined = new Metadata();
      combined.merge(firstHeaders);
      combined.merge(headers);
      delegate.apply(combined);
    }

    @Override
    public void fail(Status status) {
      delegate.fail(status);
    }
  }
}
