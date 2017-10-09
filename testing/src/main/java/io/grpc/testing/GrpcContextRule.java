/*
 * Copyright, 1999-2017, SALESFORCE.com
 * All Rights Reserved
 * Company Confidential
 */

package io.grpc.testing;

import io.grpc.Context;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * {@code GrpcContextRule} is a JUnit {@link TestRule} that forcibly resets the gRPC
 * {@link Context} to {@link Context#ROOT} between every unit test.
 *
 * <p>This rule makes it easier to correctly implement correct unit tests by preventing the
 * accidental leakage of context state between tests.
 */
public class GrpcContextRule implements TestRule {
  @Override
  public Statement apply(final Statement base, Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        // Reset the gRPC context between test executions
        Context.ROOT.attach();
        base.evaluate();
      }
    };
  }
}
