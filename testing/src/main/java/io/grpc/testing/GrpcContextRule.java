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

public class GrpcContextRule implements TestRule {
  @Override
  public Statement apply(final Statement base, Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        Context previous = Context.ROOT.attach();
        try {
          base.evaluate();
        } finally {
          Context.ROOT.detach(previous);
        }
      }
    };
  }
}
