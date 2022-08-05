package io.grpc.util;

import static org.mockito.Mockito.mock;

import io.grpc.LoadBalancer;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link OutlierDetectionLoadBalancer}. */
@RunWith(JUnit4.class)
public class OutlierDetectionLoadBalancerTest {

  private final LoadBalancer mockDelegate = mock(LoadBalancer.class);

}
