/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.netty;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.grpc.Attributes;
import io.grpc.Status;
import io.grpc.internal.DisconnectError;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.SimpleDisconnectError;
import io.netty.handler.codec.http2.StreamBufferingEncoder;


/** Maintainer of transport lifecycle status. */
final class ClientTransportLifecycleManager {
  private final ManagedClientTransport.Listener listener;
  private boolean transportReady;
  private boolean transportShutdown;
  private boolean transportInUse;
  /** null iff !transportShutdown. */
  private Status shutdownStatus;
  /** The DisconnectError that produced shutdownStatus. Valid iff shutdownStatus != null. */
  private DisconnectError shutdownDisconnectError;
  /** null iff !transportShutdown. */
  private boolean transportTerminated;

  public ClientTransportLifecycleManager(ManagedClientTransport.Listener listener) {
    this.listener = listener;
  }

  public Attributes filterAttributes(Attributes attributes) {
    if (transportReady || transportShutdown) {
      return attributes;
    }
    return listener.filterTransport(attributes);
  }

  public void notifyReady() {
    if (transportReady || transportShutdown) {
      return;
    }
    transportReady = true;
    listener.transportReady();
  }

  /**
   * Marks transport as shutdown, but does not set the error status. This must eventually be
   * followed by a call to notifyShutdown.
   */
  public void notifyGracefulShutdown(Status s, DisconnectError disconnectError) {
    if (transportShutdown) {
      return;
    }
    transportShutdown = true;
    listener.transportShutdown(s, disconnectError);
  }

  /** Returns {@code true} if was the first shutdown. */
  @CanIgnoreReturnValue
  public boolean notifyShutdown(Status s, DisconnectError disconnectError) {
    notifyGracefulShutdown(s, disconnectError);
    if (shutdownStatus != null) {
      // The original shutdown was self-initiated (a graceful subchannel/channel shutdown) iff
      // it was reported with SUBCHANNEL_SHUTDOWN. This is a reliable signal, unlike checking
      // for a Throwable cause: the genuine network-death path (NettyClientHandler
      // #channelInactive's default "Network closed for unknown reason") has no cause either.
      boolean wasSelfInitiated =
          shutdownDisconnectError == SimpleDisconnectError.SUBCHANNEL_SHUTDOWN;

      // Some exceptions are just artifacts of the channel closing and carry no real
      // diagnostic value; don't let them count as "a real error" for the purposes of the
      // upgrade below.
      boolean isRoutineClosureArtifact =
          s.getCause() instanceof java.nio.channels.ClosedChannelException
          || s.getCause() instanceof StreamBufferingEncoder.Http2ChannelClosedException;
      
      // Status Upgrade: an external event should replace a self-initiated shutdown status,
      // unless the external event is itself just a routine closure artifact.
      if (wasSelfInitiated
          && disconnectError != SimpleDisconnectError.SUBCHANNEL_SHUTDOWN
          && !isRoutineClosureArtifact) {
        shutdownStatus = s;
        shutdownDisconnectError = disconnectError;
      }
      return false;
    }
    shutdownStatus = s;
    shutdownDisconnectError = disconnectError;
    return true;
  }

  public void notifyInUse(boolean inUse) {
    if (inUse == transportInUse) {
      return;
    }
    transportInUse = inUse;
    listener.transportInUse(inUse);
  }

  public void notifyTerminated(Status s, DisconnectError disconnectError) {
    if (transportTerminated) {
      return;
    }
    transportTerminated = true;
    notifyShutdown(s, disconnectError);
    listener.transportTerminated();
  }

  public Status getShutdownStatus() {
    return shutdownStatus;
  }

}
