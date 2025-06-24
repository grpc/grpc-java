/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.okhttp;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.grpc.okhttp.OkHttpProtocolNegotiator.AndroidNegotiator;
import io.grpc.okhttp.internal.Platform;
import io.grpc.okhttp.internal.Platform.TlsExtensionType;
import io.grpc.okhttp.internal.Protocol;
import java.io.IOException;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;

/**
 * Tests for {@link OkHttpProtocolNegotiator}.
 */
@RunWith(JUnit4.class)
public class OkHttpProtocolNegotiatorTest {
  private final SSLSocket sock = mock(SSLSocket.class);
  private final Platform platform = mock(Platform.class);

  @Test
  public void createNegotiator_isAndroid() {
    ClassLoader cl = new ClassLoader(this.getClass().getClassLoader()) {
      @Override
      protected Class<?> findClass(String name) throws ClassNotFoundException {
        // Just don't throw.
        if ("com.android.org.conscrypt.OpenSSLSocketImpl".equals(name)) {
          return null;
        }
        return super.findClass(name);
      }
    };

    OkHttpProtocolNegotiator negotiator = OkHttpProtocolNegotiator.createNegotiator(cl);
    assertEquals(AndroidNegotiator.class, negotiator.getClass());
  }

  @Test
  public void createNegotiator_isAndroidLegacy() {
    ClassLoader cl = new ClassLoader(this.getClass().getClassLoader()) {
      @Override
      protected Class<?> findClass(String name) throws ClassNotFoundException {
        // Just don't throw.
        if ("org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl".equals(name)) {
          return null;
        }
        return super.findClass(name);
      }
    };

    OkHttpProtocolNegotiator negotiator = OkHttpProtocolNegotiator.createNegotiator(cl);

    assertEquals(AndroidNegotiator.class, negotiator.getClass());
  }

  @Test
  public void createNegotiator_notAndroid() {
    ClassLoader cl = new ClassLoader(this.getClass().getClassLoader()) {
      @Override
      protected Class<?> findClass(String name) throws ClassNotFoundException {
        if ("com.android.org.conscrypt.OpenSSLSocketImpl".equals(name)) {
          throw new ClassNotFoundException();
        }
        return super.findClass(name);
      }
    };

    OkHttpProtocolNegotiator negotiator = OkHttpProtocolNegotiator.createNegotiator(cl);

    assertEquals(OkHttpProtocolNegotiator.class, negotiator.getClass());
  }

  @Test
  public void negotiatorNotNull() {
    assertNotNull(OkHttpProtocolNegotiator.get());
  }

  @Test
  public void negotiate_handshakeFails() throws IOException {
    SSLParameters parameters = new SSLParameters();
    OkHttpProtocolNegotiator negotiator = OkHttpProtocolNegotiator.get();
    doReturn(parameters).when(sock).getSSLParameters();
    doThrow(new IOException()).when(sock).startHandshake();
    assertThrows(IOException.class,
        () -> negotiator.negotiate(sock, "hostname", ImmutableList.of(Protocol.HTTP_2)));
  }

  @Test
  public void negotiate_noSelectedProtocol() {
    Platform platform = mock(Platform.class);

    OkHttpProtocolNegotiator negotiator = new OkHttpProtocolNegotiator(platform);

    RuntimeException e = assertThrows(RuntimeException.class,
        () -> negotiator.negotiate(sock, "hostname", ImmutableList.of(Protocol.HTTP_2)));
    assertThat(e).hasMessageThat().isEqualTo("TLS ALPN negotiation failed with protocols: [h2]");
  }

  @Test
  public void negotiate_success() throws Exception {
    when(platform.getSelectedProtocol(ArgumentMatchers.<SSLSocket>any())).thenReturn("h2");
    OkHttpProtocolNegotiator negotiator = new OkHttpProtocolNegotiator(platform);

    String actual = negotiator.negotiate(sock, "hostname", ImmutableList.of(Protocol.HTTP_2));

    assertEquals("h2", actual);
    verify(sock).startHandshake();
    verify(platform).getSelectedProtocol(sock);
    verify(platform).afterHandshake(sock);
  }

  // Checks that the super class is properly invoked.
  @Test
  public void negotiate_android_handshakeFails() {
    when(platform.getTlsExtensionType()).thenReturn(TlsExtensionType.ALPN_AND_NPN);
    AndroidNegotiator negotiator = new AndroidNegotiator(platform);

    FakeAndroidSslSocket androidSock = new FakeAndroidSslSocket() {
      @Override
      public void startHandshake() throws IOException {
        throw new IOException("expected");
      }
    };

    IOException e = assertThrows(IOException.class,
        () -> negotiator.negotiate(androidSock, "hostname", ImmutableList.of(Protocol.HTTP_2)));
    assertThat(e).hasMessageThat().isEqualTo("expected");
  }

  @VisibleForTesting
  public static class FakeAndroidSslSocketAlpn extends FakeAndroidSslSocket {
    @Override
    public byte[] getAlpnSelectedProtocol() {
      return "h2".getBytes(UTF_8);
    }
  }

  @Test
  public void getSelectedProtocol_alpn() throws Exception {
    when(platform.getTlsExtensionType()).thenReturn(TlsExtensionType.ALPN_AND_NPN);
    AndroidNegotiator negotiator = new AndroidNegotiator(platform);
    FakeAndroidSslSocket androidSock = new FakeAndroidSslSocketAlpn();

    String actual = negotiator.getSelectedProtocol(androidSock);

    assertEquals("h2", actual);
  }

  @VisibleForTesting
  public static class FakeAndroidSslSocketNpn extends FakeAndroidSslSocket {
    @Override
    public byte[] getNpnSelectedProtocol() {
      return "h2".getBytes(UTF_8);
    }
  }

  @Test
  public void getSelectedProtocol_npn() throws Exception {
    when(platform.getTlsExtensionType()).thenReturn(TlsExtensionType.NPN);
    AndroidNegotiator negotiator = new AndroidNegotiator(platform);
    FakeAndroidSslSocket androidSock = new FakeAndroidSslSocketNpn();

    String actual = negotiator.getSelectedProtocol(androidSock);

    assertEquals("h2", actual);
  }

  // A fake of org.conscrypt.OpenSSLSocketImpl
  @VisibleForTesting // Must be public for reflection to work
  public static class FakeAndroidSslSocket extends SSLSocket {

    public void setUseSessionTickets(boolean arg) {}

    public void setHostname(String arg) {}

    public byte[] getAlpnSelectedProtocol() {
      return null;
    }

    public void setAlpnProtocols(byte[] arg) {}

    public byte[] getNpnSelectedProtocol() {
      return null;
    }

    public void setNpnProtocols(byte[] arg) {}

    @Override
    public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {}

    @Override
    public boolean getEnableSessionCreation() {
      return false;
    }

    @Override
    public String[] getEnabledCipherSuites() {
      return null;
    }

    @Override
    public String[] getEnabledProtocols() {
      return null;
    }

    @Override
    public boolean getNeedClientAuth() {
      return false;
    }

    @Override
    public SSLSession getSession() {
      return null;
    }

    @Override
    public String[] getSupportedCipherSuites() {
      return null;
    }

    @Override
    public String[] getSupportedProtocols() {
      return null;
    }

    @Override
    public boolean getUseClientMode() {
      return false;
    }

    @Override
    public boolean getWantClientAuth() {
      return false;
    }

    @Override
    public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {}

    @Override
    public void setEnableSessionCreation(boolean flag) {}

    @Override
    public void setEnabledCipherSuites(String[] suites) {}

    @Override
    public void setEnabledProtocols(String[] protocols) {}

    @Override
    public void setNeedClientAuth(boolean need) {}

    @Override
    public void setUseClientMode(boolean mode) {}

    @Override
    public void setWantClientAuth(boolean want) {}

    @Override
    public void startHandshake() throws IOException {}
  }

  @Test
  public void isValidHostName_withUnderscore() {
    assertFalse(OkHttpProtocolNegotiator.isValidHostName("test_cert_2"));
  }
}
