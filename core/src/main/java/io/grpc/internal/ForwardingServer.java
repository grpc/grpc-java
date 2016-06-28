/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import com.google.common.base.Preconditions;

import io.grpc.Server;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Server implementation that delegates to another implementation. */
public abstract class ForwardingServer<T extends ForwardingServer<T>> extends Server {
  protected abstract Server delegate();

  @SuppressWarnings("unchecked")
  private T thisT() {
    return (T) this;
  }

  @Override
  public T start() throws IOException {
    delegate().start();
    return thisT();
  }

  @Override
  public int getPort() {
    return delegate().getPort();
  }

  @Override
  public T shutdown() {
    delegate().shutdown();
    return thisT();
  }

  @Override
  public T shutdownNow() {
    delegate().shutdownNow();
    return thisT();
  }

  @Override
  public boolean isShutdown() {
    return delegate().isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return delegate().isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate().awaitTermination(timeout, unit);
  }

  @Override
  public void awaitTermination() throws InterruptedException {
    delegate().awaitTermination();
  }

  public static class SimpleForwardingServer<T extends SimpleForwardingServer<T>>
      extends ForwardingServer<T> {
    private final Server server;

    public SimpleForwardingServer(Server server) {
      this.server = Preconditions.checkNotNull(server, "server");
    }

    @Override
    protected Server delegate() {
      return server;
    }
  }
}
