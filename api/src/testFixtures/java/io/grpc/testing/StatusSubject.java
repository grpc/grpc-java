/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.testing;

import static com.google.common.truth.Fact.fact;

import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;
import io.grpc.Status;
import javax.annotation.Nullable;

/** Propositions for {@link Status} subjects. */
public final class StatusSubject extends Subject {

  private static final Subject.Factory<StatusSubject, Status> statusFactory = new Factory();

  public static Subject.Factory<StatusSubject, Status> status() {
    return statusFactory;
  }

  private final Status actual;

  private StatusSubject(FailureMetadata metadata, @Nullable Status subject) {
    super(metadata, subject);
    this.actual = subject;
  }

  /** Fails if the subject is not OK. */
  public void isOk() {
    if (actual == null) {
      failWithActual("expected to be OK but was", "null");
    } else if (!actual.isOk()) {
      failWithoutActual(
          fact("expected to be OK but was", actual.getCode()),
          fact("description", actual.getDescription()),
          fact("cause", actual.getCause()));
    }
  }

  /** Fails if the subject does not have the given code. */
  public void hasCode(Status.Code expectedCode) {
    if (actual == null) {
      failWithActual("expected to have code " + expectedCode + " but was", "null");
    } else {
      check("getCode()").that(actual.getCode()).isEqualTo(expectedCode);
    }
  }

  private static final class Factory implements Subject.Factory<StatusSubject, Status> {
    @Override
    public StatusSubject createSubject(FailureMetadata metadata, @Nullable Status that) {
      return new StatusSubject(metadata, that);
    }
  }
}
