/*
 * Copyright, 1999-2017, SALESFORCE.com
 * All Rights Reserved
 * Company Confidential
 */

package io.grpc.testing;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.Context;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.MethodSorters;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class GrpcContextRuleTest {
  @Rule public final GrpcContextRule contextRule = new GrpcContextRule();

  private static Context.Key<String> key = Context.key("key");

  @Test
  public void badAttach() {
    Context ctx = Context.current();
    // Never do this for real
    ctx.withValue(key, "value").attach();

    ctx = Context.current();
    assertThat(key.get()).isNotEmpty();
  }

  @Test
  public void verifyEmptyContext() {
    assertThat(key.get()).isNull();
  }
}
