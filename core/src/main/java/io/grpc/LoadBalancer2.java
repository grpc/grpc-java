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

package io.grpc;

import com.google.common.base.Preconditions;

import java.util.List;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

@NotThreadSafe
public abstract class LoadBalancer2 {
  // All methods are run in the LoadBalancer executor, a serializing
  // executor.
  public abstract void handleResolvedAddresses(
      List<ResolvedServerInfoGroup> servers, Attributes attributes);
  public abstract void handleNameResolutionError(Status error);
  // Supersedes handleTransportReady() and handleTransportShutdown()
  public abstract void handleSubchannelState(
      Subchannel subchannel, ConnectivityStateInfo stateInfo);
  public abstract void shutdown();


  // Where the LoadBalancer implement the main routing logic
  @ThreadSafe
  public static abstract class SubchannelPicker {
    public abstract PickResult pickSubchannel(Attributes affinity, Metadata headers);
  }

  @Immutable
  public static final class PickResult {
    // A READY channel, or null
    @Nullable private final Subchannel subchannel;
    // An error to be propagated to the application if subchannel == null
    // Or OK if there is no error.
    // subchannel being null and error being OK means RPC needs to wait
    private final Status status;

    private PickResult(Subchannel subchannel, Status status) {
      this.subchannel = subchannel;
      this.status = Preconditions.checkNotNull(status, "status");
    }

    public static PickResult withSubchannel(Subchannel subchannel) {
      return new PickResult(Preconditions.checkNotNull(subchannel, "subchannel"), Status.OK);
    }

    public static PickResult withError(Status error) {
      Preconditions.checkArgument(!error.isOk(), "error status shouldn't be OK");
      return new PickResult(null, error);
    }

    public static PickResult withNothingYet() {
      return new PickResult(null, Status.OK);
    }

    @Nullable
    public Subchannel getSubchannel() {
      return subchannel;
    }

    public Status getStatus() {
      return status;
    }
  }

  // Implemented by Channel, used by LoadBalancer implementations.
  @ThreadSafe
  public abstract static class Helper {
    // Subchannel for making RPCs. Wraps a TransportSet
    public abstract Subchannel createSubChannel(EquivalentAddressGroup addrs, Attributes attrs);
    // Out-of-band channel for LoadBalancer’s own RPC needs, e.g., 
    // talking to an external load-balancer. Wraps a TransportSet
    public abstract ManagedChannel createOobChannel(
        EquivalentAddressGroup eag, String authority);
    // LoadBalancer calls this whenever its connectivity state changes
    // Channel will run the new picker against all pending RPCs
    public abstract void updatePicker(SubchannelPicker picker);
    // The serializing executor where the LoadBalancer methods are called
    public abstract Executor getExecutor();
    // For GRPCLB which needs to resolve the address for delegation
    public abstract NameResolver.Factory getNameResolverFactory();
    public abstract String getServiceName();
  }

  // Represents the logical connection to a certain server represented by an EquivalentAddressGroup
  @ThreadSafe
  public static abstract class Subchannel {
    public abstract void shutdown();
    public abstract void requestConnection();
    public abstract EquivalentAddressGroup getAddresses();
    // The same Attributes passed to createSubChannel.
    // LoadBalancer can use it to attach additional information here, e.g.,
    // the shard this Subchannel belongs to.
    public abstract Attributes getAttributes();
  }

  @ThreadSafe
  public static abstract class Factory {
    public abstract LoadBalancer2 newLoadBalancer(Helper helper);
  }
}
