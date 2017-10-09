/*
 * Copyright 2016, gRPC Authors All rights reserved.
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

import io.grpc.Context;
import org.junit.Assert;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.logging.Logger;

/**
 * {@code GrpcContextRule} is a JUnit {@link TestRule} that forcibly resets the gRPC
 * {@link Context} to {@link Context#ROOT} between every unit test.
 *
 * <p>This rule makes it easier to correctly implement correct unit tests by preventing the
 * accidental leakage of context state between tests.
 */
public class GrpcContextRule implements TestRule {
  private static final Logger log = Logger.getLogger(GrpcContextRule.class.getName());
  private boolean failOnLeak;

  public GrpcContextRule failIfTestLeaksContext() {
    failOnLeak = true;
    return this;
  }

  @Override
  public Statement apply(final Statement base, final Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        // Reset the gRPC context between test executions
        Context.ROOT.attach();
        base.evaluate();
        if (Context.current() != Context.ROOT) {
          if (failOnLeak) {
            Assert.fail("Test is leaking context state between tests! Ensure proper "
                    + "attach()/detach() pairing.");
          } else {
            log.severe("Unit test " + description.getDisplayName() + " is leaking context "
                    + "state between tests! Ensure proper attach()/detach() pairing.");
          }
        }
      }
    };
  }
}
