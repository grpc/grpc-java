package io.grpc.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import io.grpc.ConnectivityState;

public class MultiChildLoadBalancerTest {
    @Test
    public void testAggregateState() {
        // Test when both states are null
        ConnectivityState overallState = null;
        ConnectivityState childState = null;
        ConnectivityState result = MultiChildLoadBalancer.aggregateState(overallState, childState);
        assertEquals(null, result);

        // Test when overall state is null
        overallState = null;
        childState = ConnectivityState.CONNECTING;
        result = MultiChildLoadBalancer.aggregateState(overallState, childState);
        assertEquals(ConnectivityState.CONNECTING, result);

        // Test when child state is null
        overallState = ConnectivityState.READY;
        childState = null;
        result = MultiChildLoadBalancer.aggregateState(overallState, childState);
        assertEquals(ConnectivityState.READY, result);

        // Test when both states are READY
        overallState = ConnectivityState.READY;
        childState = ConnectivityState.READY;
        result = MultiChildLoadBalancer.aggregateState(overallState, childState);
        assertEquals(ConnectivityState.READY, result);

        // Test when either state is READY
        overallState = ConnectivityState.IDLE;
        childState = ConnectivityState.READY;
        result = MultiChildLoadBalancer.aggregateState(overallState, childState);
        assertEquals(ConnectivityState.READY, result);

        // Test when both states are IDLE
        overallState = ConnectivityState.IDLE;
        childState = ConnectivityState.IDLE;
        result = MultiChildLoadBalancer.aggregateState(overallState, childState);
        assertEquals(ConnectivityState.IDLE, result);
    }
}
