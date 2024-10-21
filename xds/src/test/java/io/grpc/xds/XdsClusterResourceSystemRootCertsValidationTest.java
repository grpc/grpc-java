package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableSet;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CombinedCertificateValidationContext;
import io.grpc.xds.client.XdsResourceType.ResourceInvalidException;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class XdsClusterResourceSystemRootCertsValidationTest {

  @Parameterized.Parameters(name = "{0}")
  public static List<Object> data() {
    return Arrays.asList(true, false);
  }

  @Parameterized.Parameter(value = 0)
  public boolean enableSystemRootCerts;

  @Before
  public void setUp() {
    System.setProperty("GRPC_EXPERIMENTAL_XDS_SYSTEM_ROOT_CERTS", Boolean.toString(enableSystemRootCerts));
  }

  @After
  public void tearDown() {
    System.clearProperty("GRPC_EXPERIMENTAL_XDS_SYSTEM_ROOT_CERTS");
  }

  @Test
  public void validateCommonTlsContext_combinedValidationContextSystemRootCerts()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setDefaultValidationContext(
                    CertificateValidationContext.newBuilder()
                        .setSystemRootCerts(
                            CertificateValidationContext.SystemRootCerts.newBuilder().build())
                        .build()
                )
                .build())
        .build();
    try {
      XdsClusterResource
          .validateCommonTlsContext(commonTlsContext, ImmutableSet.of(), false);
      if (!enableSystemRootCerts) {
        fail("Expected validation exception.");
      }
    } catch(ResourceInvalidException ex) {
      if (enableSystemRootCerts) {
        fail("Unexpected validation exception: " + ex.getMessage());
      } else {
        assertThat(ex.getMessage()).isEqualTo(
            "ca_certificate_provider_instance or system_root_certs is required in "
                + "upstream-tls-context");
      }
    }
  }

  @Test
  public void validateCommonTlsContext_validationContextSystemRootCerts()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setValidationContext(
            CertificateValidationContext.newBuilder()
                .setSystemRootCerts(
                    CertificateValidationContext.SystemRootCerts.newBuilder().build())
                .build())
        .build();
    try {
      XdsClusterResource
          .validateCommonTlsContext(commonTlsContext, ImmutableSet.of(), false);
      if (!enableSystemRootCerts) {
        fail("Expected validation exception.");
      }
    } catch(ResourceInvalidException ex) {
      if (enableSystemRootCerts) {
        fail("Unexpected validation exception: " + ex.getMessage());
      } else {
        assertThat(ex.getMessage()).isEqualTo(
            "ca_certificate_provider_instance or system_root_certs is required in "
                + "upstream-tls-context");
      }
    }
  }
}
