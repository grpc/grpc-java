/*
 * Copyright 2015, Google Inc. All rights reserved.
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

package io.grpc.callable;

import com.google.common.base.Preconditions;

import io.grpc.ExperimentalApi;
import io.grpc.MethodDescriptor;

import javax.annotation.Nullable;

/**
 * Describes meta data for a {@link Callable}.
 */
@ExperimentalApi
class CallableDescriptor<RequestT, ResponseT> {

  /**
   * Constructs a descriptor from grpc descriptor.
   */
  public static <RequestT, ResponseT> CallableDescriptor<RequestT, ResponseT>
      create(MethodDescriptor<RequestT, ResponseT> grpcDescriptor) {
    return new CallableDescriptor<RequestT, ResponseT>(grpcDescriptor);
  }

  private final MethodDescriptor<RequestT, ResponseT> descriptor;

  private CallableDescriptor(MethodDescriptor<RequestT, ResponseT> descriptor) {
    this.descriptor = Preconditions.checkNotNull(descriptor);
  }

  /**
   * Returns the grpc method descriptor.
   */
  public MethodDescriptor<RequestT, ResponseT> getMethodDescriptor() {
    return descriptor;
  }

  /**
   * Returns a page descriptor if one is derivable from the callable descriptor, null if not.
   * By default, this returns null, but sub-classes may override this.
   */
  @Nullable public <ResourceT> PageDescriptor<RequestT, ResponseT, ResourceT>
  getPageDescriptor(@SuppressWarnings("unused") Class<ResourceT> resourceType) {
    return null;
  }
}
