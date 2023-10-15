package io.grpc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.grpc.ServerCall;
import io.grpc.internal.NoopServerCall;
import org.junit.Test;

public class SerializingServerCallTest {

  @Test
  public void testMethods() {
    ServerCall<Integer, Integer> testCall = new SerializingServerCall<>(new NoopServerCall<>());
    testCall.setCompression("gzip");
    testCall.setMessageCompression(true);
    assertTrue(testCall.isReady());
    assertFalse(testCall.isCancelled());
    assertNull(testCall.getAuthority());
    assertNotNull(testCall.getAttributes());
  }
}
