/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.s2a.handshaker;

import io.grpc.stub.StreamObserver;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.logging.Logger;

/** A fake S2Av2 server that should be used for testing only. */
public final class FakeS2AServer extends S2AServiceGrpc.S2AServiceImplBase {
  private static final Logger logger = Logger.getLogger(FakeS2AServer.class.getName());

  private final FakeWriter writer;

  public FakeS2AServer() throws InvalidKeySpecException, NoSuchAlgorithmException {
    this.writer = new FakeWriter();
    this.writer.setVerificationResult(FakeWriter.VerificationResult.SUCCESS).initializePrivateKey();
  }

  @Override
  public StreamObserver<SessionReq> setUpSession(StreamObserver<SessionResp> responseObserver) {
    return new StreamObserver<SessionReq>() {
      @Override
      public void onNext(SessionReq req) {
        logger.info("Received a request from client.");
        responseObserver.onNext(writer.handleResponse(req));
      }

      @Override
      public void onError(Throwable t) {
        responseObserver.onError(t);
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
      }
    };
  }
}