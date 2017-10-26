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

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Context;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import org.junit.Assert;
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
  private static final Logger log = Logger.getLogger(GrpcContextRule.class.getName());
  private final Context root;
  private boolean failOnLeak;

  public GrpcContextRule() {
    // Context storage implementations can define an alternative root context for threads with with no attached context.
    // To capture this, create a virgin thread and extract its context. Use that context in lieu of Context.ROOT.
    // While paranoid, this prevents contexts leaked by other tests classes from poisoning Context.current().
    ListeningExecutorService executorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    try {
      root = executorService.submit(new Callable<Context>() {
        @Override
        public Context call() throws Exception {
          return Context.current();
        }
      }).get();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    } finally {
      executorService.shutdown();
    }
  }

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
        Context prev = root.attach();
        try {
          base.evaluate();
          if (Context.current() != root) {
            if (failOnLeak) {
              Assert.fail("Test is leaking context state between tests! Ensure proper "
                      + "attach()/detach() pairing.");
            } else {
              log.severe("Unit test " + description.getDisplayName() + " is leaking context "
                      + "state between tests! Ensure proper attach()/detach() pairing.");
            }
          }
        } finally {
          root.detach(prev);
        }
      }
    };
  }
}
