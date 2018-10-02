package io.grpc.okhttp;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.okhttp.DelegatingFrameWriter.getLogLevel;

import java.io.IOException;
import java.util.logging.Level;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DelegatingFrameWriterTest {
  @Test
  public void unknownException() {
    assertThat(getLogLevel(new Exception())).isEqualTo(Level.INFO);
  }

  @Test
  public void quiet() {
    assertThat(getLogLevel(new IOException("Socket closed"))).isEqualTo(Level.FINE);
  }

  @Test
  public void nonquiet() {
    assertThat(getLogLevel(new IOException("foo"))).isEqualTo(Level.INFO);
  }

  @Test
  public void nullMessage() {
    IOException e = new IOException();
    assertThat(e.getMessage()).isNull();
    assertThat(getLogLevel(e)).isEqualTo(Level.INFO);
  }
}