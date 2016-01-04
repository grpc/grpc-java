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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Set;

/**
 * Compression Negotiators act on behalf of a {@link ClientCall} or {@link ServerCall} to set up
 * compression between endpoints.  This class provides a default implementation that will try to
 * advertise all known decoders to the remote end point, and then decide on which
 * {@link Compressor} or {@link Decompressor} to use when sending and receiving messages.
 *
 * <p>This class is also designed to be overriden to provide custom compression negotiation on a
 * per-call basis.  This means that it is possible to pick a different codec based on service,
 * method, call, or other criteria.  Because of this, overriding behavior should be done carefully
 * to ensure that the gRPC protocol is not violated.  Specifically, you MUST only advertise codecs
 * you are capable of decoding, and you must only pick an encoding that the remote endpoint can
 * decode.
 */
@ExperimentalApi
public class CompressionNegotiator {

  private final CompressorRegistry compressorRegistry;
  private final DecompressorRegistry decompressorRegistry;

  public CompressionNegotiator(
      CompressorRegistry compressorRegistry, DecompressorRegistry decompressorRegistry) {
    this.compressorRegistry = checkNotNull(compressorRegistry, "compressorRegistry");
    this.decompressorRegistry = checkNotNull(decompressorRegistry, "decompressorRegistry");
  }

  public CompressionNegotiator() {
    this(CompressorRegistry.getDefaultInstance(), DecompressorRegistry.getDefaultInstance());
  }

  /**
   * Called after the remote endpoint has provided headers, but before the local headers have been
   * sent.
   *
   * <p>For clients, this is called with the encodings the server has previously sent in other
   * calls.  Thus for the first call it is possible that the known encodings could be empty.  By
   * default this method picks the first compressor that the CompressorRegistry supports and that
   * the remote endpoint supports.  The order is not defined for iteration through the compressor
   * registry.  The order is not defined for knownRemoteAcceptEncodings, even if the remote
   * endpoint provided them in an order.
   *
   * @param knownRemoteAcceptEncodings encodings the remote endpoint is known to support
   * @return A compressor that may be used to encode messages.
   */
  public Compressor pickCompressor(Set<String> knownRemoteAcceptEncodings) {
    for (String supportedEncoding : compressorRegistry.getKnownMessageEncodings()) {
      if (knownRemoteAcceptEncodings.contains(supportedEncoding)) {
        return compressorRegistry.lookupCompressor(supportedEncoding);
      }
    }

    return Codec.Identity.NONE;
  }

  /**
   * Returns a set of strings representing what decoders to put in the grpc-accept-encoding header.
   * The default implementation uses the results from the decompressorRegistry.
   *
   * <p>Results are inherently unordered, since most implementations ignore order.  It would be
   * possible to override this method and return a Set with a specific iteration order.  However,
   * there is not a substantial benefit to doing so.
   */
  public Set<String> advertiseDecompressors() {
    return decompressorRegistry.getAdvertisedMessageEncodings();
  }

  /**
   * Picks a decompressor based on the received grpc-encoding header.  This method may not be
   * called if the remote end point does not provide an encoding header.
   *
   * @param remoteEncoding the encoding of messages to be sent by the remote endpoint
   * @return a decompressor capable of decoding messages encoded with {@code remoteEncoding}
   * @throws IllegalArgumentException if a decompressor cannot be found
   */
  public Decompressor pickDecompressor(String remoteEncoding) {
    Decompressor d = decompressorRegistry.lookupDecompressor(remoteEncoding);
    checkArgument(d != null, "Unabled to find decompressor for %s", remoteEncoding);

    return d;
  }
}

