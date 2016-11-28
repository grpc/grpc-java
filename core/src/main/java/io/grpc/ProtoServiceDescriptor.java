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

import java.util.Arrays;
import java.util.Collection;

/**
 * Descriptor for a service defined in a proto file.
 */
public class ProtoServiceDescriptor extends ServiceDescriptor {
  private final String fileDescriptor;
  private final String filename;

  /** 
   * @param name service name.
   * @param fileDescriptor serialized protobuf file descriptor.
   * @param filename the name of the proto file where this service is defined.
   * @param methods method descriptors.
   */
  public ProtoServiceDescriptor(String name, String fileDescriptor, String filename,
      MethodDescriptor<?, ?>... methods) {
    super(name, Arrays.asList(methods));
    this.fileDescriptor = Preconditions.checkNotNull(fileDescriptor, "fileDescriptor");
    this.filename = Preconditions.checkNotNull(filename, "filename");
  }

  /** 
   * @param name service name.
   * @param fileDescriptor serialized protobuf file descriptor.
   * @param filename the name of the proto file where this service is defined.
   * @param methods method descriptors.
   */
  public ProtoServiceDescriptor(String name, String fileDescriptor, String filename,
      Collection<MethodDescriptor<?, ?>> methods) {
    super(name, methods);
    this.fileDescriptor = Preconditions.checkNotNull(fileDescriptor, "fileDescriptor");
    this.filename = Preconditions.checkNotNull(filename, "filename");
  }

  /** Returns this ProtoServiceDescriptor. */
  public ProtoServiceDescriptor getProtoServiceDescriptor() {
    return this;
  }
  
  /** Returns the file name where this service is defined. */
  public String getFilename() {
    return filename;
  }

  /** Returns a serialized proto file descriptor. */
  public String getFileDescriptor() {
    return fileDescriptor;
  }
}
