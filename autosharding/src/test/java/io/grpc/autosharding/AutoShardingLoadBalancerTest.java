/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.autosharding;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.cloud.autosharding.v1.AssignmentChunk;
import com.google.cloud.autosharding.v1.AssignmentMetadata;
import com.google.cloud.autosharding.v1.AutoshardingServiceGrpc;
import com.google.cloud.autosharding.v1.EndpointState;
import com.google.cloud.autosharding.v1.PerSliceEndpointState;
import com.google.cloud.autosharding.v1.SliceAssignment;
import com.google.cloud.autosharding.v1.WatchShardingAssignmentRequest;
import com.google.cloud.autosharding.v1.WatchShardingAssignmentResponse;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.InternalEquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickDetailsConsumer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.FakeClock;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.TestMethodDescriptors;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link AutoShardingLoadBalancer}.
 *
 * <p>These drive a real {@link AutoshardingClient} against an in-process fake sharding service,
 * so the path from a served assignment through to a routed pick is covered end to end.
 */
@RunWith(JUnit4.class)
public class AutoShardingLoadBalancerTest {
  private static final String CHANNEL_FACTORY_KEY = "shard-service-key";
  private static final String OTHER_CHANNEL_FACTORY_KEY = "other-shard-service-key";
  private static final String UNKNOWN_CHANNEL_FACTORY_KEY = "unknown-key";
  private static final String TARGET = "autosharding-target";
  private static final String KEY_HEADER = "x-shard-key";
  private static final String OTHER_KEY_HEADER = "x-other-shard-key";
  private static final long ASSIGNMENT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
  private static final long POLL_TIMEOUT_SECONDS = 5;
  private static final MethodDescriptor<Void, Void> METHOD = TestMethodDescriptors.voidMethod();

  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final SynchronizationContext syncContext =
      new SynchronizationContext(
          (t, e) -> {
            throw new AssertionError(e);
          });
  private final FakeClock fakeClock = new FakeClock();
  private final FakeAutoshardingService service = new FakeAutoshardingService();
  private final Helper helper = mock(Helper.class);
  private final FakeChildProvider childProvider = new FakeChildProvider();
  private final FakeChannelFactory channelFactory = new FakeChannelFactory();

  private Channel shardingChannel;
  private AutoShardingLoadBalancer loadBalancer;

  @Nullable private ConnectivityState currentState;
  @Nullable private SubchannelPicker currentPicker;
  @Nullable private StreamObserver<WatchShardingAssignmentResponse> serverStream;
  private int balancingStateUpdates;

  @Before
  public void setUp() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(service)
            .build()
            .start());
    shardingChannel =
        grpcCleanup.register(InProcessChannelBuilder.forName(serverName).directExecutor().build());

    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(fakeClock.getScheduledExecutorService());
    doAnswer(
            invocation -> {
              currentState = invocation.getArgument(0);
              currentPicker = invocation.getArgument(1);
              balancingStateUpdates++;
              return null;
            })
        .when(helper)
        .updateBalancingState(any(ConnectivityState.class), any(SubchannelPicker.class));

    loadBalancer =
        new AutoShardingLoadBalancer(
            helper,
            childProvider,
            () -> () -> TimeUnit.SECONDS.toNanos(1),
            fakeClock.getStopwatchSupplier(),
            "client-uuid");
  }

  @After
  public void tearDown() {
    // Must run before GrpcCleanupRule terminates the channel, otherwise the assignment client
    // keeps retrying against a shutting-down channel.
    syncContext.execute(loadBalancer::shutdown);
  }

  // ---------------------------------------------------------------------------------------------
  // Configuration handling
  // ---------------------------------------------------------------------------------------------

  @Test
  public void emptyKeyHeaderName_isRejected() {
    // There would be no header to read the routing key from. C++ rejects this at config-parse
    // time.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AutoShardingLoadBalancerConfig(
                CHANNEL_FACTORY_KEY, TARGET, "", true, ASSIGNMENT_TIMEOUT_NANOS));
  }

  @Test
  public void missingChannelFactory_fallbackEnabled_failsRpcsAnyway() {
    Status status = deliverWithoutChannelFactory(config(CHANNEL_FACTORY_KEY, true), "a");

    // Fallback only covers the sharding service; without a factory the update itself fails.
    assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("k").getStatus().getDescription()).contains("no channel factory");
  }

  @Test
  public void missingChannelFactory_fallbackDisabled_failsRpcs() {
    Status status = deliverWithoutChannelFactory(config(CHANNEL_FACTORY_KEY, false), "a");

    assertThat(status.isOk()).isFalse();
    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("k").getStatus().getDescription()).contains("no channel factory");
  }

  @Test
  public void missingChannelFactory_onKeyChange_failsAndKeepsTheOldChannel() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, false), "a");
    deliverAssignment(1, slice("", "a"));
    reportReady("a");

    Status status =
        deliverWithoutChannelFactory(config(OTHER_CHANNEL_FACTORY_KEY, false), "a");

    assertThat(status.isOk()).isFalse();
    assertThat(channelFactory.released).isEmpty();
    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("k").getStatus().getDescription()).contains("no channel factory");
  }

  @Test
  public void missingChannelFactory_sameKey_failsAndKeepsTheChannel() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, false), "a");
    deliverAssignment(1, slice("", "a"));
    reportReady("a");

    // The factory is required on every update, even when the key is unchanged.
    Status status = deliverWithoutChannelFactory(config(CHANNEL_FACTORY_KEY, false), "a");

    assertThat(status.isOk()).isFalse();
    assertThat(channelFactory.released).isEmpty();
    assertThat(service.streamCount.get()).isEqualTo(1);
    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("k").getStatus().getDescription()).contains("no channel factory");
  }

  @Test
  public void newChannelFactoryInstance_sameKey_keepsTheChannelAndClient() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, false), "a");
    deliverAssignment(1, slice("", "a"));
    reportReady("a");

    // The key identifies the channel, so another factory for the same key changes nothing.
    FakeChannelFactory otherFactory = new FakeChannelFactory();
    deliverWithChannelFactory(otherFactory, config(CHANNEL_FACTORY_KEY, false), "a");

    assertThat(otherFactory.keys).isEmpty();
    assertThat(channelFactory.released).isEmpty();
    assertThat(service.streamCount.get()).isEqualTo(1);
    assertThat(pickedHost(pick("k"))).isEqualTo("a");
  }

  @Test
  public void newChannelFactoryInstance_thenKeyChange_releasesThroughTheCreatingFactory() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    FakeChannelFactory otherFactory = new FakeChannelFactory();
    deliverWithChannelFactory(otherFactory, config(CHANNEL_FACTORY_KEY, true), "a");

    deliverWithChannelFactory(otherFactory, config(OTHER_CHANNEL_FACTORY_KEY, true), "a");

    assertThat(otherFactory.keys).containsExactly(OTHER_CHANNEL_FACTORY_KEY);
    assertThat(channelFactory.isReleased(0)).isTrue();
    assertThat(otherFactory.released).isEmpty();
  }

  @Test
  public void unknownChannelFactoryKey_fallbackEnabled_failsRpcsAnyway() {
    Status status = deliverAddresses(config(UNKNOWN_CHANNEL_FACTORY_KEY, true), "a");

    assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("k").getStatus().getDescription())
        .contains("channel factory rejected key '" + UNKNOWN_CHANNEL_FACTORY_KEY + "'");
  }

  @Test
  public void unknownChannelFactoryKey_fallbackDisabled_failsRpcsWithTheFactoryError() {
    Status status = deliverAddresses(config(UNKNOWN_CHANNEL_FACTORY_KEY, false), "a");

    assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("k").getStatus().getDescription())
        .contains("channel factory rejected key '" + UNKNOWN_CHANNEL_FACTORY_KEY + "'");
  }

  @Test
  public void channelFactoryFailure_sameKey_isRetriedOnTheNextUpdate() {
    deliverAddresses(config(UNKNOWN_CHANNEL_FACTORY_KEY, false), "a");
    assertThat(channelFactory.attempts).isEqualTo(1);

    Status status = deliverAddresses(config(UNKNOWN_CHANNEL_FACTORY_KEY, false), "a", "b");

    // The failed config was never applied, so the same key still looks like a change.
    assertThat(status.isOk()).isFalse();
    assertThat(channelFactory.attempts).isEqualTo(2);
    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("k").getStatus().getDescription())
        .contains("channel factory rejected key '" + UNKNOWN_CHANNEL_FACTORY_KEY + "'");
  }

  @Test
  public void missingChannelFactory_thenSuppliedUnderTheSameKey_createsTheChannel() {
    deliverWithoutChannelFactory(config(CHANNEL_FACTORY_KEY, true), "a");

    Status status = deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");

    assertThat(status.isOk()).isTrue();
    assertThat(channelFactory.keys).containsExactly(CHANNEL_FACTORY_KEY);
    assertThat(service.streamCount.get()).isEqualTo(1);
  }

  @Test
  public void channelFactoryFailure_keepsThePreviousChannel() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, false), "a");
    deliverAssignment(1, slice("", "a"));
    reportReady("a");

    Status status = deliverAddresses(config(UNKNOWN_CHANNEL_FACTORY_KEY, false), "a");

    assertThat(status.isOk()).isFalse();
    assertThat(channelFactory.released).isEmpty();
    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("k").getStatus().getDescription()).contains("channel factory rejected key");
  }

  @Test
  public void channelFactoryFailure_thenRecovers_queuesUntilTheNewClientReports()
      throws Exception {
    deliverAddresses(config(UNKNOWN_CHANNEL_FACTORY_KEY, false), "a");

    Status status = deliverAddresses(config(CHANNEL_FACTORY_KEY, false), "a");
    reportReady("a");

    assertThat(status.isOk()).isTrue();
    assertThat(channelFactory.keys).containsExactly(CHANNEL_FACTORY_KEY);
    PickResult queued = pick("k");
    assertThat(queued.getStatus().isOk()).isTrue();
    assertThat(queued.getSubchannel()).isNull();

    deliverAssignment(1, slice("", "a"));

    assertThat(pickedHost(pick("k"))).isEqualTo("a");
  }

  @Test
  public void noEndpoints_reportsTransientFailureAndFailsRpcs() {
    Status status = deliverAddresses(config(CHANNEL_FACTORY_KEY, true));

    assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("anything").getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
  }

  @Test
  public void endpointsRetracted_thenRestored_resumesServing() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    deliverAssignment(1, slice("", "a"));

    deliverAddresses(config(CHANNEL_FACTORY_KEY, true));
    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);

    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    reportReady("a");

    assertThat(currentState).isEqualTo(READY);
    assertThat(pickedHost(pick("k"))).isEqualTo("a");
  }

  @Test
  public void endpointsRetracted_shutsDownTheChildren() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    deliverAssignment(1, slice("", "a"));
    reportReady("a");

    Status status = deliverAddresses(config(CHANNEL_FACTORY_KEY, true));

    assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(childForHost("a").shutdown).isTrue();
  }

  @Test
  public void endpointsRetracted_newAssignment_staysFailed() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    deliverAssignment(1, slice("", "a"));

    deliverAddresses(config(CHANNEL_FACTORY_KEY, true));
    deliverAssignment(2, slice("", "a"));

    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("k").getStatus().getDescription()).contains("no endpoints");
  }

  @Test
  public void missingChannelFactory_stillUpdatesTheEndpoints() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, false), "a");
    reportReady("a");

    Status status = deliverWithoutChannelFactory(config(CHANNEL_FACTORY_KEY, false), "b");

    assertThat(status.isOk()).isFalse();
    assertThat(childForHost("a").shutdown).isTrue();
  }

  @Test
  public void channelFactoryFailure_stillUpdatesTheEndpoints() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, false), "a");
    reportReady("a");

    Status status = deliverAddresses(config(UNKNOWN_CHANNEL_FACTORY_KEY, false), "b");

    assertThat(status.isOk()).isFalse();
    assertThat(childForHost("a").shutdown).isTrue();
  }

  @Test
  public void channelFactoryKeyChangedWhileEndpointsEmpty_appliesOnceEndpointsReturn() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    assertThat(channelFactory.keys).containsExactly(CHANNEL_FACTORY_KEY);

    // Nothing from an update without endpoints is applied, so the key is still seen as changed
    // by the next update.
    deliverAddresses(config(OTHER_CHANNEL_FACTORY_KEY, true));

    assertThat(channelFactory.keys).containsExactly(CHANNEL_FACTORY_KEY);
    assertThat(channelFactory.released).isEmpty();

    deliverAddresses(config(OTHER_CHANNEL_FACTORY_KEY, true), "a");

    assertThat(channelFactory.keys)
        .containsExactly(CHANNEL_FACTORY_KEY, OTHER_CHANNEL_FACTORY_KEY)
        .inOrder();
    assertThat(channelFactory.isReleased(0)).isTrue();
  }

  @Test
  public void keyHeaderNameChangedWhileEndpointsEmpty_stillTakesEffect() throws Exception {
    deliverAddresses(configWithKeyHeader(KEY_HEADER), "a", "b");
    deliverAssignment(1, slice("", "a"), slice("m", "b"));

    deliverAddresses(configWithKeyHeader(OTHER_KEY_HEADER));
    deliverAddresses(configWithKeyHeader(OTHER_KEY_HEADER), "a", "b");
    reportReady("a");
    reportReady("b");

    // Read under the new header, "z" is past "m" and belongs to "b". Were the policy still
    // reading the old header, it would find nothing and fail the pick.
    assertThat(pickedHost(pick(OTHER_KEY_HEADER, "z"))).isEqualTo("b");
  }

  @Test
  public void missingKeyHeader_failsPickEvenWithFallback() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    deliverAssignment(1, slice("", "a"), slice("m", "b"));
    reportReady("a");
    reportReady("b");

    PickResult result = pick(OTHER_KEY_HEADER, "z");

    assertThat(result.isDrop()).isTrue();
    assertThat(result.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription()).contains(KEY_HEADER);
  }

  // ---------------------------------------------------------------------------------------------
  // Channel to the sharding service
  // ---------------------------------------------------------------------------------------------

  @Test
  public void firstUpdate_createsChannelAndOpensStream() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");

    assertThat(channelFactory.keys).containsExactly(CHANNEL_FACTORY_KEY);
    WatchShardingAssignmentRequest request = takeRequest();
    assertThat(request.getInitialClientConfig().getTarget()).isEqualTo(TARGET);
    assertThat(request.getInitialClientConfig().getClientUuid()).isEqualTo("client-uuid");
  }

  @Test
  public void unchangedKey_reusesChannelAndStream() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    takeRequest();

    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");

    assertThat(channelFactory.keys).containsExactly(CHANNEL_FACTORY_KEY);
    assertThat(service.streamCount.get()).isEqualTo(1);
  }

  @Test
  public void changedKey_createsNewChannelClosesOldAndRestartsStream() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    takeRequest();

    deliverAddresses(config(OTHER_CHANNEL_FACTORY_KEY, true), "a");

    assertThat(channelFactory.keys)
        .containsExactly(CHANNEL_FACTORY_KEY, OTHER_CHANNEL_FACTORY_KEY)
        .inOrder();
    assertThat(channelFactory.isReleased(0)).isTrue();
    assertThat(channelFactory.isReleased(1)).isFalse();
    assertThat(service.streamCount.get()).isEqualTo(2);
  }

  @Test
  public void changedKey_cancelsOldStreamBeforeReleasingOldChannel() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    takeRequest();

    deliverAddresses(config(OTHER_CHANNEL_FACTORY_KEY, true), "a");

    assertThat(channelFactory.isReleased(0)).isTrue();
    assertThat(channelFactory.liveCallsAtRelease).containsExactly(0);
  }

  @Test
  public void changedTarget_restartsStreamWithoutNewChannel() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    takeRequest();

    deliverAddresses(retargetedConfig("other-target"), "a");

    assertThat(channelFactory.keys).containsExactly(CHANNEL_FACTORY_KEY);
    assertThat(service.streamCount.get()).isEqualTo(2);
    assertThat(takeRequest().getInitialClientConfig().getTarget()).isEqualTo("other-target");
  }

  @Test
  public void targetWithLocalityToken_allEndpointsInOneLocality_isSubstituted()
      throws Exception {
    // What a child of weighted_target sees under xDS: only its own locality's endpoints.
    deliverEndpoints(
        retargetedConfig("target/%s"),
        endpointInLocality("a", "us-central1-a"),
        endpointInLocality("b", "us-central1-a"));

    assertThat(takeRequest().getInitialClientConfig().getTarget())
        .isEqualTo("target/us-central1-a");
  }

  @Test
  public void targetWithTwoLocalityTokens_substitutesOnlyTheFirst() throws Exception {
    deliverEndpoints(retargetedConfig("target/%s/%s"), endpointInLocality("a", "us-central1-a"));

    assertThat(takeRequest().getInitialClientConfig().getTarget())
        .isEqualTo("target/us-central1-a/%s");
  }

  @Test
  public void changedLocality_createsANewClientEvenThoughTheConfigIsUnchanged() throws Exception {
    AutoShardingLoadBalancerConfig localityConfig = retargetedConfig("target/%s");
    deliverEndpoints(localityConfig, endpointInLocality("a", "us-central1-a"));
    takeRequest();

    deliverEndpoints(localityConfig, endpointInLocality("a", "us-central1-b"));

    assertThat(channelFactory.keys).containsExactly(CHANNEL_FACTORY_KEY);
    assertThat(service.streamCount.get()).isEqualTo(2);
    assertThat(takeRequest().getInitialClientConfig().getTarget())
        .isEqualTo("target/us-central1-b");
  }

  @Test
  public void targetWithLocalityToken_someEndpointsWithoutLocality_substitutesEmptyString()
      throws Exception {
    deliverEndpoints(
        retargetedConfig("target/%s"),
        endpointInLocality("a", "us-central1-a"),
        endpoints("b").get(0));

    assertThat(takeRequest().getInitialClientConfig().getTarget()).isEqualTo("target/");
  }

  @Test
  public void targetWithLocalityToken_endpointsRetracted_keepsTheLocality() throws Exception {
    AutoShardingLoadBalancerConfig localityConfig = retargetedConfig("target/%s");
    deliverEndpoints(localityConfig, endpointInLocality("a", "us-central1-a"));
    takeRequest();

    // An empty update carries no locality, and must not move the client to "target/" and then
    // back again once the endpoints return.
    deliverEndpoints(localityConfig);
    deliverEndpoints(localityConfig, endpointInLocality("a", "us-central1-a"));

    assertThat(service.streamCount.get()).isEqualTo(1);
  }

  @Test
  public void targetChangedWhileEndpointsRetracted_appliesOnceEndpointsReturn()
      throws Exception {
    deliverEndpoints(retargetedConfig("target/%s"), endpointInLocality("a", "us-central1-a"));
    takeRequest();

    deliverEndpoints(retargetedConfig("other/%s"));

    assertThat(service.streamCount.get()).isEqualTo(1);

    deliverEndpoints(retargetedConfig("other/%s"), endpointInLocality("a", "us-central1-a"));

    assertThat(takeRequest().getInitialClientConfig().getTarget())
        .isEqualTo("other/us-central1-a");
  }

  @Test
  public void targetWithLocalityToken_noLocality_substitutesEmptyString() throws Exception {
    deliverAddresses(retargetedConfig("target/%s"), "a");

    assertThat(takeRequest().getInitialClientConfig().getTarget()).isEqualTo("target/");
  }

  @Test
  public void targetWithLocalityToken_endpointsSpanLocalities_substitutesEmptyString()
      throws Exception {
    // The policy is doing its own locality picking, so it sees endpoints from every locality.
    // gRFC A119 says the token is not meant to be used here; the target must not end up depending
    // on which endpoint the resolver happened to list first.
    deliverEndpoints(
        retargetedConfig("target/%s"),
        endpointInLocality("a", "us-central1-a"),
        endpointInLocality("b", "us-central1-b"));

    assertThat(takeRequest().getInitialClientConfig().getTarget()).isEqualTo("target/");
  }

  @Test
  public void targetWithLocalityToken_endpointReordering_doesNotRecreateTheClient()
      throws Exception {
    AutoShardingLoadBalancerConfig localityConfig = retargetedConfig("target/%s");
    deliverEndpoints(
        localityConfig,
        endpointInLocality("a", "us-central1-a"),
        endpointInLocality("b", "us-central1-b"));
    takeRequest();

    // Same endpoints, other order. Sourcing the locality from the first endpoint would change the
    // target here and reconnect the client to a different sharding resource.
    deliverEndpoints(
        localityConfig,
        endpointInLocality("b", "us-central1-b"),
        endpointInLocality("a", "us-central1-a"));

    assertThat(service.streamCount.get()).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------------
  // Startup: queuing, timeout and fallback
  // ---------------------------------------------------------------------------------------------

  @Test
  public void beforeFirstAssignment_queuesRpcs() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");

    // The policy connects lazily, so it starts IDLE rather than CONNECTING. That also keeps a
    // parent priority policy from failing over while the sharding service is still answering.
    assertThat(currentState).isEqualTo(IDLE);
    PickResult result = pick("anything");
    assertThat(result.getSubchannel()).isNull();
    assertThat(result.getStatus().isOk()).isTrue();
  }

  @Test
  public void beforeFirstAssignment_childStateChangesDoNotUnqueueRpcs() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    activate("a");
    reportReady("a");

    // The reported state follows the endpoints, but RPCs stay queued rather than being routed
    // anywhere arbitrary while the sharding service has not answered.
    assertThat(currentState).isEqualTo(READY);
    assertThat(pick("k").getSubchannel()).isNull();
  }

  @Test
  public void beforeFirstAssignment_endpointsFailing_reportsTransientFailureButKeepsQueuing() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");

    reportTransientFailure("a");
    reportTransientFailure("b");

    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("k").getSubchannel()).isNull();
    assertThat(pick("k").getStatus().isOk()).isTrue();
  }

  @Test
  public void beforeFirstAssignment_endpointInTransientFailure_stillWakesUpAnIdleEndpoint() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b", "c");

    reportTransientFailure("a");

    // RPCs stay queued, but the endpoint aggregate is CONNECTING with nothing connecting, so
    // one IDLE endpoint is nudged exactly as it would be outside the wait.
    assertThat(currentState).isEqualTo(CONNECTING);
    assertThat(pick("k").getSubchannel()).isNull();
    assertThat(childForHost("b").requestConnectionCount).isEqualTo(1);
  }

  @Test
  public void beforeFirstAssignment_allEndpointsIdle_connectsNothing() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");

    pick("k");

    assertThat(childProvider.children).isEmpty();
  }

  @Test
  public void initialAssignmentTimeout_fallbackEnabled_spreadsAcrossAllEndpoints() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    reportReady("a");
    reportReady("b");

    fakeClock.forwardNanos(ASSIGNMENT_TIMEOUT_NANOS);

    assertThat(currentState).isEqualTo(READY);
    assertThat(pickedHost(pick("k"))).isAnyOf("a", "b");
  }

  @Test
  public void initialAssignmentTimeout_fallbackDisabled_failsRpcs() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, false), "a");
    reportReady("a");

    fakeClock.forwardNanos(ASSIGNMENT_TIMEOUT_NANOS);

    PickResult result = pick("k");
    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(result.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription()).contains("timed out");
  }

  @Test
  public void assignmentBeforeTimeout_cancelsTheTimer() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);

    deliverAssignment(1, slice("", "a"));

    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void newChannel_keepsServingPreviousAssignmentWhileTimerPending() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    deliverAssignment(1, slice("", "a"), slice("m", "b"));
    reportReady("a");
    reportReady("b");
    assertThat(pickedHost(pick("z"))).isEqualTo("b");

    // Switching sharding service must not interrupt traffic.
    deliverAddresses(config(OTHER_CHANNEL_FACTORY_KEY, true), "a", "b");

    assertThat(pickedHost(pick("z"))).isEqualTo("b");
    assertThat(pickedHost(pick("a"))).isEqualTo("a");
  }

  @Test
  public void newChannel_restartsTheInitialAssignmentTimer() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    deliverAssignment(1, slice("", "a"));
    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);

    deliverAddresses(config(OTHER_CHANNEL_FACTORY_KEY, true), "a");

    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);
  }

  @Test
  public void newChannel_timeoutOfTheNewClient_replacesThePreviousAssignment() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, false), "a");
    deliverAssignment(1, slice("", "a"));
    reportReady("a");

    deliverAddresses(config(OTHER_CHANNEL_FACTORY_KEY, false), "a");
    assertThat(pickedHost(pick("k"))).isEqualTo("a");

    fakeClock.forwardNanos(ASSIGNMENT_TIMEOUT_NANOS);

    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("k").getStatus().getDescription()).contains("timed out");
  }

  @Test
  public void changedTarget_restartsTheInitialAssignmentTimer() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    deliverAssignment(1, slice("", "a"));

    deliverAddresses(retargetedConfig("other-target"), "a");

    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);
  }

  @Test
  public void changedTarget_inFallback_keepsServingInsteadOfQueuing() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    reportReady("a");
    reportReady("b");
    fakeClock.forwardNanos(ASSIGNMENT_TIMEOUT_NANOS);
    assertThat(pickedHost(pick("k"))).isAnyOf("a", "b");

    deliverAddresses(retargetedConfig("other-target"), "a", "b");

    // The previous client's error is kept until the new client reports.
    assertThat(currentState).isEqualTo(READY);
    assertThat(pickedHost(pick("k"))).isAnyOf("a", "b");
  }

  @Test
  public void changedTarget_whileTimerPending_restartsTheDeadline() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    reportReady("a");
    fakeClock.forwardNanos(ASSIGNMENT_TIMEOUT_NANOS - 1);

    deliverAddresses(retargetedConfig("other-target"), "a");
    fakeClock.forwardNanos(1);
    assertThat(pick("k").getSubchannel()).isNull();

    fakeClock.forwardNanos(ASSIGNMENT_TIMEOUT_NANOS - 1);

    assertThat(pickedHost(pick("k"))).isEqualTo("a");
  }

  @Test
  public void unusableAssignment_beforeAnyAssignment_entersFallback() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    reportReady("a");
    reportReady("b");

    pushUnusableAssignment(1);

    assertThat(currentState).isEqualTo(READY);
    assertThat(pickedHost(pick("k"))).isAnyOf("a", "b");
  }

  @Test
  public void unusableAssignment_beforeAnyAssignment_fallbackDisabled_failsRpcs() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, false), "a");
    reportReady("a");

    pushUnusableAssignment(1);

    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("k").getStatus().getDescription()).contains("no usable slices");
  }

  @Test
  public void streamFailure_beforeAnyAssignment_entersFallback() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    reportReady("a");
    reportReady("b");

    currentServerStream().onError(Status.UNAVAILABLE.asRuntimeException());

    assertThat(currentState).isEqualTo(READY);
    assertThat(pickedHost(pick("k"))).isAnyOf("a", "b");
  }

  @Test
  public void unusableAssignment_thenGoodOneBeforeTimeout_usesTheGoodOne() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    reportReady("a");
    reportReady("b");

    pushUnusableAssignment(1);
    deliverAssignment(2, slice("", "a"), slice("m", "b"));

    assertThat(pickedHost(pick("alpha"))).isEqualTo("a");
    assertThat(pickedHost(pick("zulu"))).isEqualTo("b");
    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void unusableAssignment_afterAGoodOne_keepsServingTheGoodOne() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    deliverAssignment(1, slice("", "a"), slice("m", "b"));
    reportReady("a");
    reportReady("b");

    pushUnusableAssignment(2);

    assertThat(pickedHost(pick("alpha"))).isEqualTo("a");
    assertThat(pickedHost(pick("zulu"))).isEqualTo("b");
  }

  // ---------------------------------------------------------------------------------------------
  // Routing on assignments
  // ---------------------------------------------------------------------------------------------

  @Test
  public void assignmentRoutesByKeyRange() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    deliverAssignment(1, slice("", "a"), slice("m", "b"));
    reportReady("a");
    reportReady("b");

    assertThat(pickedHost(pick("alpha"))).isEqualTo("a");
    assertThat(pickedHost(pick("zulu"))).isEqualTo("b");
  }

  @Test
  public void assignmentNamingUnknownHostname_dropsIt() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, false), "a");
    // The sharding service still believes "ghost" is serving; the resolver disagrees.
    deliverAssignment(1, slice("", "ghost"));
    reportReady("a");

    PickResult result = pick("k");
    assertThat(result.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
  }

  @Test
  public void assignmentNamingUnknownHostname_fallbackEnabled_usesFallbackPool() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    deliverAssignment(1, slice("", "ghost"));
    reportReady("a");

    assertThat(pickedHost(pick("k"))).isEqualTo("a");
  }

  @Test
  public void resolverUpdateAfterAssignment_rebuildsSliceMapWithNewIndices() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    deliverAssignment(1, slice("", "b"));
    reportReady("a");
    reportReady("b");
    assertThat(pickedHost(pick("k"))).isEqualTo("b");

    // "b" moves from index 1 to index 0. If the slice map were not rebuilt, the stale index
    // would now route to "a".
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "b", "a");

    assertThat(pickedHost(pick("k"))).isEqualTo("b");
  }

  @Test
  public void staleGenerationAssignment_isIgnored() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    deliverAssignment(5, slice("", "a"));
    reportReady("a");
    reportReady("b");
    assertThat(pickedHost(pick("k"))).isEqualTo("a");

    pushAssignment(3, slice("", "b"));

    assertThat(pickedHost(pick("k"))).isEqualTo("a");
  }

  // ---------------------------------------------------------------------------------------------
  // Child state updates and aggregated connectivity state
  // ---------------------------------------------------------------------------------------------

  @Test
  public void childStateUpdate_republishesPicker() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    deliverAssignment(1, slice("", "a"));
    int updatesBefore = balancingStateUpdates;

    reportReady("a");

    assertThat(balancingStateUpdates).isGreaterThan(updatesBefore);
    assertThat(currentState).isEqualTo(READY);
    assertThat(pickedHost(pick("k"))).isEqualTo("a");
  }

  @Test
  public void allEndpointsIdle_reportsIdle() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    deliverAssignment(1, slice("", "a"));

    assertThat(currentState).isEqualTo(IDLE);
  }

  @Test
  public void twoEndpointsInTransientFailure_reportsTransientFailure() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    deliverAssignment(1, slice("", "a"), slice("m", "b"));

    reportTransientFailure("a");
    reportTransientFailure("b");

    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
  }

  @Test
  public void oneEndpointInTransientFailure_wakesUpAnIdleEndpoint() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b", "c");
    deliverAssignment(1, slice("", "a"));

    reportTransientFailure("a");

    // Aggregated state is CONNECTING, and nothing was connecting, so exactly one IDLE endpoint
    // is nudged so the policy can recover without needing a pick.
    assertThat(currentState).isEqualTo(CONNECTING);
    assertThat(childProvider.children).hasSize(2);
    assertThat(childForHost("b").requestConnectionCount).isEqualTo(1);
  }

  @Test
  public void endpointAlreadyConnecting_noAdditionalWakeUp() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b", "c");
    deliverAssignment(1, slice("", "a"));
    activate("b");

    reportTransientFailure("a");

    // "b" is already CONNECTING, so "c" is left alone.
    assertThat(currentState).isEqualTo(CONNECTING);
    assertThat(activatedHostnames()).containsExactly("a", "b");
  }

  @Test
  public void requestConnection_wakesUpAnIdleEndpoint() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");

    syncContext.execute(loadBalancer::requestConnection);

    assertThat(childProvider.children).hasSize(1);
  }

  @Test
  public void picksOnIdleEndpointTriggerConnection() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    deliverAssignment(1, slice("", "a"));
    assertThat(childProvider.children).isEmpty();

    PickResult result = pick("k");

    assertThat(result.getSubchannel()).isNull();
    assertThat(childProvider.children).hasSize(1);
  }

  // ---------------------------------------------------------------------------------------------
  // Name resolution errors and shutdown
  // ---------------------------------------------------------------------------------------------

  @Test
  public void nameResolutionError_withKnownEndpoints_keepsServing() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    deliverAssignment(1, slice("", "a"));
    reportReady("a");

    syncContext.execute(
        () -> loadBalancer.handleNameResolutionError(Status.UNAVAILABLE.withDescription("boom")));

    assertThat(currentState).isEqualTo(READY);
    assertThat(pickedHost(pick("k"))).isEqualTo("a");
  }

  @Test
  public void nameResolutionError_whileIdleWithAnAssignment_keepsServing() throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    deliverAssignment(1, slice("", "a"));
    assertThat(currentState).isEqualTo(IDLE);

    syncContext.execute(
        () -> loadBalancer.handleNameResolutionError(Status.UNAVAILABLE.withDescription("boom")));

    // IDLE endpoints report nothing until picked, so a failing picker published here would never
    // be replaced; the lazy policy must keep its picker so that picks connect.
    assertThat(currentState).isEqualTo(IDLE);
    PickResult result = pick("k");
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(childProvider.children).hasSize(1);
  }

  @Test
  public void nameResolutionError_whileIdleInFallback_keepsServing() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    fakeClock.forwardNanos(ASSIGNMENT_TIMEOUT_NANOS);
    assertThat(currentState).isEqualTo(IDLE);

    syncContext.execute(
        () -> loadBalancer.handleNameResolutionError(Status.UNAVAILABLE.withDescription("boom")));

    assertThat(currentState).isEqualTo(IDLE);
    assertThat(pick("k").getStatus().isOk()).isTrue();
    assertThat(childProvider.children).hasSize(1);
  }

  @Test
  public void nameResolutionError_withNoEndpoints_reportsTransientFailure() {
    syncContext.execute(
        () -> loadBalancer.handleNameResolutionError(Status.UNAVAILABLE.withDescription("boom")));

    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("k").getStatus().getDescription()).contains("boom");
  }

  @Test
  public void nameResolutionError_withEndpointsButNoneReady_reportsTheResolverError()
      throws Exception {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    deliverAssignment(1, slice("", "a"));
    reportTransientFailure("a");

    syncContext.execute(
        () -> loadBalancer.handleNameResolutionError(Status.UNAVAILABLE.withDescription("boom")));

    // We are not serving, so the resolver failure is the more useful thing to report. Leaving it
    // out would make RPCs blame the endpoints we can no longer refresh.
    assertThat(currentState).isEqualTo(TRANSIENT_FAILURE);
    assertThat(pick("k").getStatus().getDescription()).contains("boom");
  }

  @Test
  public void nameResolutionError_whileAwaitingInitialAssignment_keepsQueueing() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a", "b");
    // Not READY, so only the initial assignment wait can be holding these RPCs.
    reportTransientFailure("a");
    assertThat(currentState).isEqualTo(CONNECTING);

    syncContext.execute(
        () -> loadBalancer.handleNameResolutionError(Status.UNAVAILABLE.withDescription("boom")));

    // gRFC A119 holds RPCs until the initial assignment timer fires; a failed refresh of
    // endpoints we already have must not cut that short.
    assertThat(currentState).isEqualTo(CONNECTING);
    assertThat(pick("k").getStatus().isOk()).isTrue();
    assertThat(pick("k").getSubchannel()).isNull();
  }

  @Test
  public void shutdown_closesChannelAndChildren() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    activate("a");

    syncContext.execute(loadBalancer::shutdown);

    assertThat(channelFactory.isReleased(0)).isTrue();
    assertThat(childProvider.children.get(0).shutdown).isTrue();
  }

  @Test
  public void shutdown_isIdempotent() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");

    syncContext.execute(loadBalancer::shutdown);
    syncContext.execute(loadBalancer::shutdown);

    assertThat(channelFactory.isReleased(0)).isTrue();
  }

  @Test
  public void shutdown_cancelsInitialAssignmentTimer() {
    deliverAddresses(config(CHANNEL_FACTORY_KEY, true), "a");
    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);

    syncContext.execute(loadBalancer::shutdown);

    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private AutoShardingLoadBalancerConfig config(String channelFactoryKey, boolean enableFallback) {
    return new AutoShardingLoadBalancerConfig(
        channelFactoryKey, TARGET, KEY_HEADER, enableFallback, ASSIGNMENT_TIMEOUT_NANOS);
  }

  /** The default config with a different {@code autosharding_target}. */
  private AutoShardingLoadBalancerConfig retargetedConfig(String target) {
    return new AutoShardingLoadBalancerConfig(
        CHANNEL_FACTORY_KEY, target, KEY_HEADER, true, ASSIGNMENT_TIMEOUT_NANOS);
  }

  /** The default config with a different {@code key_header_name}. */
  private AutoShardingLoadBalancerConfig configWithKeyHeader(String keyHeaderName) {
    return new AutoShardingLoadBalancerConfig(
        CHANNEL_FACTORY_KEY, TARGET, keyHeaderName, true, ASSIGNMENT_TIMEOUT_NANOS);
  }

  private Attributes attributesWithChannelFactory() {
    return Attributes.newBuilder()
        .set(AutoShardingAttributes.ATTR_CHANNEL_FACTORY, channelFactory)
        .build();
  }

  private Status deliverEndpoints(
      AutoShardingLoadBalancerConfig config, EquivalentAddressGroup... endpoints) {
    return acceptAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.copyOf(endpoints))
            .setAttributes(attributesWithChannelFactory())
            .setLoadBalancingPolicyConfig(config)
            .build());
  }

  private Status deliverWithoutChannelFactory(
      AutoShardingLoadBalancerConfig config, String... hostnames) {
    return acceptAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(endpoints(hostnames))
            .setAttributes(Attributes.EMPTY)
            .setLoadBalancingPolicyConfig(config)
            .build());
  }

  private Status deliverAddresses(AutoShardingLoadBalancerConfig config, String... hostnames) {
    return acceptAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(endpoints(hostnames))
            .setAttributes(attributesWithChannelFactory())
            .setLoadBalancingPolicyConfig(config)
            .build());
  }

  private Status deliverWithChannelFactory(
      ChannelFactory factory, AutoShardingLoadBalancerConfig config, String... hostnames) {
    return acceptAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(endpoints(hostnames))
            .setAttributes(
                Attributes.newBuilder()
                    .set(AutoShardingAttributes.ATTR_CHANNEL_FACTORY, factory)
                    .build())
            .setLoadBalancingPolicyConfig(config)
            .build());
  }

  private Status acceptAddresses(ResolvedAddresses resolvedAddresses) {
    AtomicReference<Status> status = new AtomicReference<>();
    syncContext.execute(() -> status.set(loadBalancer.acceptResolvedAddresses(resolvedAddresses)));
    return status.get();
  }

  private static List<EquivalentAddressGroup> endpoints(String... hostnames) {
    List<EquivalentAddressGroup> eags = new ArrayList<>();
    for (String hostname : hostnames) {
      eags.add(
          new EquivalentAddressGroup(
              new NamedAddress("addr-" + hostname),
              Attributes.newBuilder()
                  .set(InternalEquivalentAddressGroup.ATTR_ADDRESS_NAME, hostname)
                  .build()));
    }
    return ImmutableList.copyOf(eags);
  }

  private static EquivalentAddressGroup endpointInLocality(String hostname, String locality) {
    return new EquivalentAddressGroup(
        new NamedAddress("addr-" + hostname),
        Attributes.newBuilder()
            .set(InternalEquivalentAddressGroup.ATTR_ADDRESS_NAME, hostname)
            .set(EquivalentAddressGroup.ATTR_LOCALITY_NAME, locality)
            .build());
  }

  /** Sends an assignment from the fake service and waits for the load balancer to apply it. */
  private void deliverAssignment(long generation, SliceSpec... slices) throws Exception {
    pushAssignment(generation, slices);
  }

  /**
   * Sends an assignment whose only slice fails validation. Nothing usable remains, so the client
   * reports an error to the load balancer instead of an assignment.
   */
  private void pushUnusableAssignment(long generation) throws Exception {
    StreamObserver<WatchShardingAssignmentResponse> serverStream = currentServerStream();
    serverStream.onNext(
        WatchShardingAssignmentResponse.newBuilder()
            .setChunk(
                AssignmentChunk.newBuilder()
                    .addEndpoints(EndpointState.newBuilder().setEndpoint("a"))
                    // Index 7 is past the end of the endpoint list above.
                    .addSliceAssignments(sliceAssignment("", null, 7)))
            .build());
    serverStream.onNext(
        WatchShardingAssignmentResponse.newBuilder()
            .setMetadata(AssignmentMetadata.newBuilder().setGeneration(generation))
            .build());
  }

  private void pushAssignment(long generation, SliceSpec... slices) throws Exception {
    StreamObserver<WatchShardingAssignmentResponse> serverStream = currentServerStream();
    List<String> endpointNames = new ArrayList<>();
    for (SliceSpec spec : slices) {
      if (!endpointNames.contains(spec.hostname)) {
        endpointNames.add(spec.hostname);
      }
    }

    AssignmentChunk.Builder chunk = AssignmentChunk.newBuilder();
    for (String name : endpointNames) {
      chunk.addEndpoints(EndpointState.newBuilder().setEndpoint(name));
    }
    for (int i = 0; i < slices.length; i++) {
      SliceSpec spec = slices[i];
      String endKey = i + 1 < slices.length ? slices[i + 1].startKey : null;
      chunk.addSliceAssignments(
          sliceAssignment(spec.startKey, endKey, endpointNames.indexOf(spec.hostname)));
    }

    serverStream.onNext(WatchShardingAssignmentResponse.newBuilder().setChunk(chunk).build());
    serverStream.onNext(
        WatchShardingAssignmentResponse.newBuilder()
            .setMetadata(AssignmentMetadata.newBuilder().setGeneration(generation))
            .build());
  }

  private static SliceSpec slice(String startKey, String hostname) {
    return new SliceSpec(startKey, hostname);
  }

  private static final class SliceSpec {
    final String startKey;
    final String hostname;

    SliceSpec(String startKey, String hostname) {
      this.startKey = startKey;
      this.hostname = hostname;
    }
  }

  private static SliceAssignment sliceAssignment(
      String startKey, @Nullable String endKey, int endpointIndex) {
    com.google.cloud.autosharding.v1.Slice.Builder slice =
        com.google.cloud.autosharding.v1.Slice.newBuilder()
            .setStartKey(ByteString.copyFromUtf8(startKey));
    if (endKey != null) {
      slice.setEndKey(ByteString.copyFromUtf8(endKey));
    }
    return SliceAssignment.newBuilder()
        .setSlice(slice)
        .addEndpoints(PerSliceEndpointState.newBuilder().setEndpointIndex(endpointIndex))
        .build();
  }

  private PickResult pick(String key) {
    return pick(KEY_HEADER, key);
  }

  private PickResult pick(String headerName, String key) {
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER), key);
    return currentPicker.pickSubchannel(
        new PickSubchannelArgsImpl(METHOD, headers, CallOptions.DEFAULT, new PickDetailsConsumer() {
        }));
  }

  /** Returns the hostname of the endpoint the pick landed on. */
  private String pickedHost(PickResult result) {
    Subchannel subchannel = result.getSubchannel();
    if (subchannel == null) {
      throw new AssertionError("Pick did not select a subchannel: " + result);
    }
    for (FakeChild child : childProvider.children) {
      if (child.subchannel == subchannel) {
        return child.hostname;
      }
    }
    throw new AssertionError("Pick returned an unrecognized subchannel");
  }

  /**
   * Instantiates the child load balancer for {@code hostname} by asking its endpoint to connect,
   * which is how the picker brings an endpoint out of IDLE at runtime.
   */
  private void activate(String hostname) {
    syncContext.execute(
        () -> {
          EndpointMap endpointMap = loadBalancer.getEndpointMap();
          int index = endpointMap.indexOf(hostname);
          if (index == -1) {
            throw new AssertionError("Unknown endpoint hostname " + hostname);
          }
          endpointMap.toPickerEndpoints().get(index).requestConnection();
        });
  }

  private void reportReady(String hostname) {
    activate(hostname);
    syncContext.execute(() -> childForHost(hostname).reportReady());
  }

  private void reportTransientFailure(String hostname) {
    activate(hostname);
    syncContext.execute(() -> childForHost(hostname).reportTransientFailure());
  }

  private FakeChild childForHost(String hostname) {
    for (FakeChild child : childProvider.children) {
      if (hostname.equals(child.hostname)) {
        return child;
      }
    }
    throw new AssertionError("No child load balancer for hostname " + hostname);
  }

  private WatchShardingAssignmentRequest takeRequest() throws Exception {
    WatchShardingAssignmentRequest request =
        service.requests.poll(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (request == null) {
      fail("timed out waiting for a request to the sharding service");
    }
    return request;
  }

  /**
   * Returns the stream the client currently has open to the sharding service, picking up a newly
   * opened one if there is any. Streams are created synchronously by the in-process transport, so
   * a non-blocking poll is enough once the first one exists.
   */
  private StreamObserver<WatchShardingAssignmentResponse> currentServerStream() throws Exception {
    StreamObserver<WatchShardingAssignmentResponse> next = service.serverStreams.poll();
    if (next != null) {
      serverStream = next;
    } else if (serverStream == null) {
      serverStream = service.serverStreams.poll(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (serverStream == null) {
        fail("timed out waiting for a stream to the sharding service");
      }
    }
    return serverStream;
  }

  /** Hostnames whose child load balancer has been instantiated, in creation order. */
  private List<String> activatedHostnames() {
    List<String> result = new ArrayList<>();
    for (FakeChild child : childProvider.children) {
      result.add(child.hostname);
    }
    return result;
  }

  /** A {@link SocketAddress} with a predictable {@link #toString}. */
  private static final class NamedAddress extends SocketAddress {
    private static final long serialVersionUID = 0L;
    private final String name;

    NamedAddress(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  /**
   * Hands out in-process channels, each wrapped so that successive borrows are distinguishable
   * even though they share one transport.
   */
  private final class FakeChannelFactory implements ChannelFactory {
    final List<String> keys = new ArrayList<>();
    final List<Channel> created = new ArrayList<>();
    final List<Channel> released = new ArrayList<>();
    // Every createChannel() call, including the ones that throw.
    int attempts;

    @Override
    public Channel createChannel(String channelFactoryKey) {
      attempts++;
      if (UNKNOWN_CHANNEL_FACTORY_KEY.equals(channelFactoryKey)) {
        throw new IllegalArgumentException("unknown channel factory key");
      }
      keys.add(channelFactoryKey);
      Channel channel = new WrappedChannel(shardingChannel);
      created.add(channel);
      return channel;
    }

    // Calls on each released channel that had not been cancelled when it was released.
    final List<Integer> liveCallsAtRelease = new ArrayList<>();

    @Override
    public void releaseChannel(Channel channel) {
      released.add(channel);
      liveCallsAtRelease.add(((WrappedChannel) channel).liveCalls);
    }

    boolean isReleased(int index) {
      Channel channel = created.get(index);
      for (Channel released : this.released) {
        if (released == channel) {
          return true;
        }
      }
      return false;
    }
  }

  /** Gives each handle a distinct channel identity over one shared transport. */
  private static final class WrappedChannel extends Channel {
    private final Channel delegate;
    // Calls created on this handle that the client has not cancelled.
    int liveCalls;

    WrappedChannel(Channel delegate) {
      this.delegate = delegate;
    }

    @Override
    public String authority() {
      return delegate.authority();
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
      liveCalls++;
      return new SimpleForwardingClientCall<ReqT, RespT>(
          delegate.newCall(methodDescriptor, callOptions)) {
        private boolean cancelled;

        @Override
        public void cancel(@Nullable String message, @Nullable Throwable cause) {
          if (!cancelled) {
            cancelled = true;
            liveCalls--;
          }
          super.cancel(message, cause);
        }
      };
    }
  }

  private static final class FakeAutoshardingService
      extends AutoshardingServiceGrpc.AutoshardingServiceImplBase {
    final BlockingQueue<WatchShardingAssignmentRequest> requests = new LinkedBlockingQueue<>();
    final BlockingQueue<StreamObserver<WatchShardingAssignmentResponse>> serverStreams =
        new LinkedBlockingQueue<>();
    final AtomicInteger streamCount = new AtomicInteger();

    @Override
    public StreamObserver<WatchShardingAssignmentRequest> watchShardingAssignment(
        StreamObserver<WatchShardingAssignmentResponse> responseObserver) {
      streamCount.incrementAndGet();
      serverStreams.add(responseObserver);
      return new StreamObserver<WatchShardingAssignmentRequest>() {
        @Override
        public void onNext(WatchShardingAssignmentRequest request) {
          requests.add(request);
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}
      };
    }
  }

  private static final class FakeChildProvider extends LoadBalancerProvider {
    final List<FakeChild> children = new ArrayList<>();

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    @Override
    public String getPolicyName() {
      return "fake_child";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper childHelper) {
      FakeChild child = new FakeChild(childHelper);
      children.add(child);
      return child;
    }
  }

  /** Stands in for {@code pick_first}, reporting CONNECTING as soon as it is asked to connect. */
  private static final class FakeChild extends LoadBalancer {
    private final Helper helper;
    final Subchannel subchannel = mock(Subchannel.class);
    @Nullable String hostname;
    int requestConnectionCount;
    boolean shutdown;

    FakeChild(Helper helper) {
      this.helper = helper;
    }

    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      hostname =
          resolvedAddresses
              .getAddresses()
              .get(0)
              .getAttributes()
              .get(InternalEquivalentAddressGroup.ATTR_ADDRESS_NAME);
      return Status.OK;
    }

    @Override
    public void handleNameResolutionError(Status error) {}

    @Override
    public void requestConnection() {
      requestConnectionCount++;
      helper.updateBalancingState(CONNECTING, new FixedResultPicker(PickResult.withNoResult()));
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    void reportReady() {
      helper.updateBalancingState(READY, new FixedResultPicker(PickResult.withSubchannel(
          subchannel)));
    }

    void reportTransientFailure() {
      helper.updateBalancingState(
          TRANSIENT_FAILURE,
          new FixedResultPicker(
              PickResult.withError(Status.UNAVAILABLE.withDescription("endpoint down"))));
    }
  }
}
