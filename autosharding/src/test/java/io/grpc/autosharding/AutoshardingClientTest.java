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
import static org.junit.Assert.fail;

import com.google.cloud.autosharding.v1.AssignmentChunk;
import com.google.cloud.autosharding.v1.AssignmentMetadata;
import com.google.cloud.autosharding.v1.AutoshardingServiceGrpc;
import com.google.cloud.autosharding.v1.EndpointState;
import com.google.cloud.autosharding.v1.LoadReportingConfig;
import com.google.cloud.autosharding.v1.PerSliceEndpointState;
import com.google.cloud.autosharding.v1.SliceAssignment;
import com.google.cloud.autosharding.v1.WatchShardingAssignmentRequest;
import com.google.cloud.autosharding.v1.WatchShardingAssignmentResponse;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AutoshardingClient}. */
@RunWith(JUnit4.class)
public class AutoshardingClientTest {
  private static final String CLIENT_UUID = "client-uuid-1";
  private static final String TARGET = "autosharding-target";
  private static final String OTHER_TARGET = "other-autosharding-target";
  private static final long TIMEOUT_SECONDS = 5;
  private static final long BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(1);
  private static final long ASSIGNMENT_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(1);

  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final SynchronizationContext syncContext =
      new SynchronizationContext(
          (t, e) -> {
            throw new AssertionError(e);
          });
  private final FakeClock fakeClock = new FakeClock();
  private final FakeAutoshardingService service = new FakeAutoshardingService();
  private final BlockingQueue<Assignment> assignments = new LinkedBlockingQueue<>();
  private final BlockingQueue<Status> errors = new LinkedBlockingQueue<>();
  private final RecordingBackoffPolicyProvider backoffPolicyProvider =
      new RecordingBackoffPolicyProvider();
  private final List<AutoshardingClient> clients = new ArrayList<>();

  private Channel channel;
  private AutoshardingClient client;

  @Before
  public void setUp() throws Exception {
    channel = newChannelToFakeService();
    client = newClient(channel, TARGET);
  }

  @After
  public void tearDown() {
    // Must happen before GrpcCleanupRule shuts the channels down, otherwise a client keeps
    // retrying against a terminating channel.
    syncContext.execute(
        () -> {
          for (AutoshardingClient created : clients) {
            created.shutdown();
          }
        });
  }

  @Test
  public void start_opensStreamAndSendsInitialClientConfig() throws Exception {
    start(client);

    WatchShardingAssignmentRequest request = takeRequest();
    assertThat(request.hasInitialClientConfig()).isTrue();
    assertThat(request.getInitialClientConfig().getTarget()).isEqualTo(TARGET);
    assertThat(request.getInitialClientConfig().getClientUuid()).isEqualTo(CLIENT_UUID);
    assertThat(request.getInitialClientConfig().getLatestGeneration()).isEqualTo(0);
  }

  @Test
  public void chunksBufferedUntilMetadata_thenAssignmentDeliveredAndAcked() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();

    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 0)));
    assertThat(assignments).isEmpty();
    assertThat(service.requests).isEmpty();

    serverStream.onNext(metadataResponse(5));

    Assignment assignment = takeAssignment();
    assertThat(assignment.getEndpointNames()).containsExactly("host-a");
    assertThat(assignment.getSlices()).hasSize(1);

    WatchShardingAssignmentRequest ack = takeRequest();
    assertThat(ack.hasAssignmentAck()).isTrue();
    assertThat(ack.getAssignmentAck().getGeneration()).isEqualTo(5);
    assertThat(ack.getAssignmentAck().getAccepted()).isTrue();
    assertThat(ack.getAssignmentAck().getErrorMessage()).isEmpty();
  }

  @Test
  public void multipleChunks_combinedIntoOneLogicalAssignment() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();

    serverStream.onNext(
        chunkResponse(AssignmentChunk.newBuilder().addEndpoints(endpoint("host-a")).build()));
    serverStream.onNext(
        chunkResponse(
            AssignmentChunk.newBuilder()
                .addEndpoints(endpoint("host-b"))
                .addSliceAssignments(sliceAssignment("", null, 1))
                .build()));
    serverStream.onNext(metadataResponse(1));

    Assignment assignment = takeAssignment();
    assertThat(assignment.getEndpointNames()).containsExactly("host-a", "host-b").inOrder();
    assertThat(assignment.getSlices().get(0).getEndpoints()).containsExactly(1);
  }

  @Test
  public void noUsableSlices_nackedAndReportedAsAnError() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();

    // Endpoint index 3 does not exist in the combined endpoint list, so the only slice is
    // dropped and nothing usable remains.
    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 3)));
    serverStream.onNext(metadataResponse(5));

    WatchShardingAssignmentRequest nack = takeRequest();
    assertThat(nack.hasAssignmentAck()).isTrue();
    assertThat(nack.getAssignmentAck().getGeneration()).isEqualTo(5);
    assertThat(nack.getAssignmentAck().getAccepted()).isFalse();
    assertThat(nack.getAssignmentAck().getErrorMessage())
        .contains("out-of-range endpoint index 3");
    assertThat(takeError().getDescription()).contains("out-of-range endpoint index 3");
    assertThat(assignments).isEmpty();
    // A rejected assignment must not advance the watermark, or the server would stop resending.
    assertThat(client.getLatestGeneration()).isEqualTo(0);
  }

  @Test
  public void someSlicesDropped_ackedWithErrorMessageAndStillDelivered() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();

    serverStream.onNext(
        chunkResponse(
            AssignmentChunk.newBuilder()
                .addEndpoints(endpoint("host-a"))
                .addSliceAssignments(sliceAssignment("", "m", 0))
                .addSliceAssignments(sliceAssignment("m", null, 3))
                .build()));
    serverStream.onNext(metadataResponse(5));

    Assignment assignment = takeAssignment();
    assertThat(assignment.getSlices()).hasSize(2);
    assertThat(assignment.getSlices().get(0).getEndpoints()).containsExactly(0);
    // The dropped slice was turned into a gap rather than invalidating the assignment.
    assertThat(assignment.getSlices().get(1).getEndpoints()).isEmpty();

    WatchShardingAssignmentRequest ack = takeRequest();
    assertThat(ack.getAssignmentAck().getAccepted()).isTrue();
    assertThat(ack.getAssignmentAck().getErrorMessage())
        .contains("out-of-range endpoint index 3");
    assertThat(errors).isEmpty();
    assertThat(client.getLatestGeneration()).isEqualTo(5);
  }

  @Test
  public void generationZero_acceptedAsTheFirstAssignment() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();

    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 0)));
    serverStream.onNext(metadataResponse(0));

    takeAssignment();
    WatchShardingAssignmentRequest ack = takeRequest();
    assertThat(ack.getAssignmentAck().getGeneration()).isEqualTo(0);
    assertThat(ack.getAssignmentAck().getAccepted()).isTrue();
  }

  @Test
  public void generationZero_resentAfterAcceptance_isStale() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();
    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 0)));
    serverStream.onNext(metadataResponse(0));
    takeAssignment();
    takeRequest(); // ACK for generation 0

    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-b", "", null, 0)));
    serverStream.onNext(metadataResponse(0));

    WatchShardingAssignmentRequest nack = takeRequest();
    assertThat(nack.getAssignmentAck().getAccepted()).isFalse();
    assertThat(nack.getAssignmentAck().getErrorMessage()).contains("stale generation");
    assertThat(assignments).isEmpty();
  }

  @Test
  public void negativeGeneration_acceptedAsTheFirstAssignment() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();

    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 0)));
    serverStream.onNext(metadataResponse(-3));

    takeAssignment();
    assertThat(client.getLatestGeneration()).isEqualTo(-3);
  }

  @Test
  public void rejectedAssignment_doesNotLeakChunksIntoTheNextOne() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();

    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 3)));
    serverStream.onNext(metadataResponse(5));
    takeRequest(); // NACK
    takeError();

    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-b", "", null, 0)));
    serverStream.onNext(metadataResponse(6));

    Assignment assignment = takeAssignment();
    assertThat(assignment.getEndpointNames()).containsExactly("host-b");
  }

  @Test
  public void staleGeneration_nackedAndNotDelivered() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();

    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 0)));
    serverStream.onNext(metadataResponse(5));
    takeAssignment();
    takeRequest(); // ACK for generation 5

    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-b", "", null, 0)));
    serverStream.onNext(metadataResponse(5));

    WatchShardingAssignmentRequest nack = takeRequest();
    assertThat(nack.getAssignmentAck().getGeneration()).isEqualTo(5);
    assertThat(nack.getAssignmentAck().getAccepted()).isFalse();
    assertThat(nack.getAssignmentAck().getErrorMessage()).contains("stale generation");
    // A stale assignment tells the LB policy nothing it does not already know.
    assertThat(assignments).isEmpty();
    assertThat(errors).isEmpty();
    assertThat(client.getLatestGeneration()).isEqualTo(5);
  }

  @Test
  public void olderGeneration_nackedAndNotDelivered() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();

    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 0)));
    serverStream.onNext(metadataResponse(5));
    takeAssignment();
    takeRequest();

    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-b", "", null, 0)));
    serverStream.onNext(metadataResponse(4));

    WatchShardingAssignmentRequest nack = takeRequest();
    assertThat(nack.getAssignmentAck().getGeneration()).isEqualTo(4);
    assertThat(nack.getAssignmentAck().getAccepted()).isFalse();
    assertThat(assignments).isEmpty();
    assertThat(client.getLatestGeneration()).isEqualTo(5);
  }

  @Test
  public void loadReportingConfig_ignored() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();

    serverStream.onNext(
        WatchShardingAssignmentResponse.newBuilder()
            .setConfig(LoadReportingConfig.newBuilder().setLoadQuantumFraction(0.5))
            .build());

    assertThat(assignments).isEmpty();
    assertThat(service.requests).isEmpty();
  }

  @Test
  public void streamFailure_reconnectsAndSendsLatestGeneration() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();

    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 0)));
    serverStream.onNext(metadataResponse(9));
    takeAssignment();
    takeRequest(); // ACK

    serverStream.onError(Status.UNAVAILABLE.asRuntimeException());
    fireRetryTimer();

    WatchShardingAssignmentRequest retryRequest = takeRequest();
    assertThat(retryRequest.hasInitialClientConfig()).isTrue();
    assertThat(retryRequest.getInitialClientConfig().getLatestGeneration()).isEqualTo(9);
    assertThat(retryRequest.getInitialClientConfig().getClientUuid()).isEqualTo(CLIENT_UUID);
    assertThat(service.streamCount.get()).isEqualTo(2);
  }

  @Test
  public void streamFailure_doesNotReconnectBeforeBackoffElapses() throws Exception {
    start(client);
    takeRequest();

    takeServerStream().onError(Status.UNAVAILABLE.asRuntimeException());

    assertThat(numPendingRetries()).isEqualTo(1);
    fakeClock.forwardNanos(BACKOFF_NANOS - 1);
    assertThat(service.streamCount.get()).isEqualTo(1);

    fakeClock.forwardNanos(1);
    takeRequest();
    assertThat(service.streamCount.get()).isEqualTo(2);
  }

  @Test
  public void streamCompletedByServer_reconnects() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();

    serverStream.onCompleted();
    fireRetryTimer();

    takeRequest();
    assertThat(service.streamCount.get()).isEqualTo(2);
  }

  @Test
  public void backoffSequence_onlyResetAfterGoodAssignment() throws Exception {
    start(client);
    takeRequest();

    // First failure with no assignment received: a backoff sequence is created.
    takeServerStream().onError(Status.UNAVAILABLE.asRuntimeException());
    fireRetryTimer();
    takeRequest();
    assertThat(backoffPolicyProvider.timesCalled).isEqualTo(1);

    // Second failure with no assignment received: the existing sequence continues.
    takeServerStream().onError(Status.UNAVAILABLE.asRuntimeException());
    fireRetryTimer();
    takeRequest();
    assertThat(backoffPolicyProvider.timesCalled).isEqualTo(1);

    // A good assignment drops the sequence, so the failure after it starts a new one.
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();
    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 0)));
    serverStream.onNext(metadataResponse(1));
    takeAssignment();
    takeRequest(); // ACK
    serverStream.onError(Status.UNAVAILABLE.asRuntimeException());
    fireRetryTimer();
    takeRequest();
    takeServerStream().onError(Status.UNAVAILABLE.asRuntimeException());
    fireRetryTimer();
    takeRequest();
    assertThat(backoffPolicyProvider.timesCalled).isEqualTo(2);
  }

  @Test
  public void streamFailureAfterGoodAssignment_reconnectsWithoutBackoff() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();
    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 0)));
    serverStream.onNext(metadataResponse(1));
    takeAssignment();
    takeRequest(); // ACK

    serverStream.onError(Status.UNAVAILABLE.asRuntimeException());
    fakeClock.runDueTasks();

    assertThat(numPendingRetries()).isEqualTo(0);
    takeRequest();
    assertThat(service.streamCount.get()).isEqualTo(2);
  }

  @Test
  public void streamFailureAfterStaleAssignmentOnly_backsOff() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();
    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 0)));
    serverStream.onNext(metadataResponse(5));
    takeAssignment();
    takeRequest(); // ACK
    serverStream.onError(Status.UNAVAILABLE.asRuntimeException());
    fireRetryTimer();
    takeRequest();

    // Only an older generation on this stream: nothing the balancer can use, so no reset.
    serverStream = takeServerStream();
    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 0)));
    serverStream.onNext(metadataResponse(3));
    serverStream.onError(Status.UNAVAILABLE.asRuntimeException());
    fakeClock.runDueTasks();

    assertThat(numPendingRetries()).isEqualTo(1);
  }

  /**
   * The LB policy answers a target or channel change by replacing the client rather than by
   * updating it, so the accepted-generation watermark never crosses over to a different server or
   * resource. See gRFC A119, "Communicating with the Autosharding service".
   */
  @Test
  public void newClient_startsFromGenerationZero() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();
    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 0)));
    serverStream.onNext(metadataResponse(9));
    takeAssignment();
    takeRequest(); // ACK
    assertThat(client.getLatestGeneration()).isEqualTo(9);
    syncContext.execute(client::shutdown);

    AutoshardingClient replacement = newClient(newChannelToFakeService(), OTHER_TARGET);
    start(replacement);

    WatchShardingAssignmentRequest request = takeRequest();
    assertThat(request.hasInitialClientConfig()).isTrue();
    assertThat(request.getInitialClientConfig().getTarget()).isEqualTo(OTHER_TARGET);
    assertThat(request.getInitialClientConfig().getLatestGeneration()).isEqualTo(0);
    assertThat(replacement.getLatestGeneration()).isEqualTo(0);
    assertThat(service.streamCount.get()).isEqualTo(2);
  }

  @Test
  public void initialAssignmentTimer_reportsAnErrorWhenItFires() throws Exception {
    start(client);
    takeRequest();

    fakeClock.forwardNanos(ASSIGNMENT_TIMEOUT_NANOS - 1);
    assertThat(errors).isEmpty();

    fakeClock.forwardNanos(1);
    Status error = takeError();
    assertThat(error.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(error.getDescription()).contains("timed out waiting for the initial assignment");
  }

  @Test
  public void initialAssignmentTimer_cancelledByTheFirstAssignment() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();
    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 0)));
    serverStream.onNext(metadataResponse(1));
    takeAssignment();

    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
    fakeClock.forwardNanos(ASSIGNMENT_TIMEOUT_NANOS);
    assertThat(errors).isEmpty();
  }

  @Test
  public void initialAssignmentTimer_notCancelledByARejectedAssignment() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();
    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 3)));
    serverStream.onNext(metadataResponse(1));
    takeError();

    fakeClock.forwardNanos(ASSIGNMENT_TIMEOUT_NANOS);
    assertThat(takeError().getDescription()).contains("timed out");
  }

  @Test
  public void streamFailure_beforeAnyAssignment_reportsAnError() throws Exception {
    start(client);
    takeRequest();

    takeServerStream().onError(Status.PERMISSION_DENIED.withDescription("nope").asException());

    Status error = takeError();
    assertThat(error.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(error.getDescription()).contains("stream failed: PERMISSION_DENIED: nope");
  }

  @Test
  public void errorsAfterTheFirstAssignment_notReported() throws Exception {
    start(client);
    takeRequest();
    StreamObserver<WatchShardingAssignmentResponse> serverStream = takeServerStream();
    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 0)));
    serverStream.onNext(metadataResponse(1));
    takeAssignment();
    takeRequest(); // ACK

    // A rejected assignment.
    serverStream.onNext(chunkResponse(chunkWithEndpoint("host-a", "", null, 3)));
    serverStream.onNext(metadataResponse(2));
    takeRequest(); // NACK
    // A stream failure, and the same on the retried stream.
    serverStream.onError(Status.UNAVAILABLE.asRuntimeException());
    fireRetryTimer();
    takeRequest();
    takeServerStream().onError(Status.UNAVAILABLE.asRuntimeException());

    assertThat(errors).isEmpty();
  }

  @Test
  public void shutdown_cancelsStreamAndStopsReconnecting() throws Exception {
    start(client);
    takeRequest();

    syncContext.execute(client::shutdown);

    assertThat(service.streamCount.get()).isEqualTo(1);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void shutdown_isIdempotent() throws Exception {
    start(client);
    takeRequest();

    syncContext.execute(client::shutdown);
    syncContext.execute(client::shutdown);

    assertThat(service.streamCount.get()).isEqualTo(1);
  }

  @Test
  public void shutdown_beforeStart_leavesNothingBehind() {
    syncContext.execute(client::shutdown);

    assertThat(service.streamCount.get()).isEqualTo(0);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  private Channel newChannelToFakeService() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(service)
            .build()
            .start());
    return grpcCleanup.register(
        InProcessChannelBuilder.forName(serverName).directExecutor().build());
  }

  /** Creates a client and registers it for shutdown, without starting it. */
  private AutoshardingClient newClient(Channel channel, String target) {
    AutoshardingClient created =
        new AutoshardingClient(
            CLIENT_UUID,
            syncContext,
            fakeClock.getScheduledExecutorService(),
            backoffPolicyProvider,
            fakeClock.getStopwatchSupplier(),
            channel,
            target,
            ASSIGNMENT_TIMEOUT_NANOS,
            new RecordingWatcher());
    clients.add(created);
    return created;
  }

  private void start(AutoshardingClient target) {
    syncContext.execute(target::start);
  }

  /** Asserts that a retry was scheduled and advances the clock so that it runs. */
  private void fireRetryTimer() {
    assertThat(numPendingRetries()).isEqualTo(1);
    fakeClock.forwardNanos(BACKOFF_NANOS);
  }

  /**
   * Pending tasks other than the initial assignment timer, which is always much further out than
   * a retry in these tests.
   */
  private int numPendingRetries() {
    int count = 0;
    for (FakeClock.ScheduledTask task : fakeClock.getPendingTasks()) {
      if (task.getDelay(TimeUnit.NANOSECONDS) <= BACKOFF_NANOS) {
        count++;
      }
    }
    return count;
  }

  private WatchShardingAssignmentRequest takeRequest() throws Exception {
    WatchShardingAssignmentRequest request =
        service.requests.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (request == null) {
      fail("timed out waiting for a request from the autosharding client");
    }
    return request;
  }

  private StreamObserver<WatchShardingAssignmentResponse> takeServerStream() throws Exception {
    StreamObserver<WatchShardingAssignmentResponse> stream =
        service.serverStreams.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (stream == null) {
      fail("timed out waiting for the autosharding client to open a stream");
    }
    return stream;
  }

  private Assignment takeAssignment() throws Exception {
    Assignment assignment = assignments.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (assignment == null) {
      fail("timed out waiting for an assignment");
    }
    return assignment;
  }

  private Status takeError() throws Exception {
    Status error = errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (error == null) {
      fail("timed out waiting for an error");
    }
    return error;
  }

  private static WatchShardingAssignmentResponse chunkResponse(AssignmentChunk chunk) {
    return WatchShardingAssignmentResponse.newBuilder().setChunk(chunk).build();
  }

  private static WatchShardingAssignmentResponse metadataResponse(long generation) {
    return WatchShardingAssignmentResponse.newBuilder()
        .setMetadata(AssignmentMetadata.newBuilder().setGeneration(generation))
        .build();
  }

  private static AssignmentChunk chunkWithEndpoint(
      String endpointName, String startKey, @Nullable String endKey, int endpointIndex) {
    return AssignmentChunk.newBuilder()
        .addEndpoints(endpoint(endpointName))
        .addSliceAssignments(sliceAssignment(startKey, endKey, endpointIndex))
        .build();
  }

  private static EndpointState endpoint(String name) {
    return EndpointState.newBuilder().setEndpoint(name).build();
  }

  private static SliceAssignment sliceAssignment(
      String startKey, @Nullable String endKey, int... endpointIndices) {
    com.google.cloud.autosharding.v1.Slice.Builder slice =
        com.google.cloud.autosharding.v1.Slice.newBuilder()
            .setStartKey(ByteString.copyFromUtf8(startKey));
    if (endKey != null) {
      slice.setEndKey(ByteString.copyFromUtf8(endKey));
    }
    SliceAssignment.Builder builder = SliceAssignment.newBuilder().setSlice(slice);
    for (int index : endpointIndices) {
      builder.addEndpoints(PerSliceEndpointState.newBuilder().setEndpointIndex(index));
    }
    return builder.build();
  }

  private final class RecordingWatcher implements AutoshardingClient.AssignmentWatcher {
    @Override
    public void onAssignment(Assignment assignment) {
      assignments.add(assignment);
    }

    @Override
    public void onError(Status error) {
      errors.add(error);
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

  /**
   * Hands out backoff policies with a fixed, non-zero delay so that retries are driven explicitly
   * by the fake clock. The number of policies handed out reflects how many times the backoff
   * sequence was reset.
   */
  private static final class RecordingBackoffPolicyProvider implements BackoffPolicy.Provider {
    int timesCalled;

    @Override
    public BackoffPolicy get() {
      timesCalled++;
      return () -> BACKOFF_NANOS;
    }
  }
}
