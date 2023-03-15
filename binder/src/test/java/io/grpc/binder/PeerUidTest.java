package io.grpc.binder;

import com.google.common.testing.EqualsTester;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PeerUidTest {

  @Test
  public void shouldImplementEqualsAndHashCode() {
    new EqualsTester()
        .addEqualityGroup(new PeerUid(123), new PeerUid(123))
        .addEqualityGroup(new PeerUid(456))
        .testEquals();
  }
}