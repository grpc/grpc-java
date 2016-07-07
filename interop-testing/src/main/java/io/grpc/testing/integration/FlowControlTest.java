package io.grpc.testing.integration;

import com.google.protobuf.ByteString;

import org.junit.Test;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.StreamRecorder;
import io.grpc.testing.integration.Messages.Payload;
import io.grpc.testing.integration.Messages.PayloadType;
import io.grpc.testing.integration.Messages.ResponseParameters;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import io.grpc.testing.integration.Messages.StreamingInputCallRequest;
import io.grpc.testing.integration.Messages.StreamingInputCallResponse;
import io.grpc.testing.integration.Messages.StreamingOutputCallRequest;
import io.grpc.testing.integration.Messages.StreamingOutputCallResponse;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

public class FlowControlTest extends AbstractInteropTest {

  private ManagedChannel channel;
  private InetAddress addr;
  private int port = 5001;
  private Clock clock;

  // in kbits
  private final int LOW_BAND = 800;
  private final int MED_BAND = 8000;
  private final int HIGH_BAND = 80000;

  // in miliseconds
  private final int LOW_LAT = 5;
  private final int MED_LAT = 125;
  private final int HIGH_LAT = 500;

  // in bytes
  private final int TINY_WINDOW = 1;
  private final int REGULAR_WINDOW = 64 * 1024;
  private final int BIG_WINDOW = 1024 * 1024;

  @Override
  public void setUp() {
    try {
      addr = InetAddress.getByName("127.127.127.127");
      startStaticServer(
          NettyServerBuilder.forAddress(new InetSocketAddress("127.127.127.127", port))
              .flowControlWindow(REGULAR_WINDOW));
      clock = Clock.systemUTC();
      channel = NettyChannelBuilder.forAddress(new InetSocketAddress(addr, port))
          .negotiationType(NegotiationType.PLAINTEXT).build();
    } catch (UnknownHostException e) {
      // do something
    }
  }

  public void doTests() {
    lowBandLowLatency();
    lowBandHighLatency();
    highBandLowLatency();
    highBandHighLatency();
    verySmallWindow();
    stopStaticServer();
  }

  private void lowBandLowLatency() {
    resetServer(REGULAR_WINDOW);
    doStream(LOW_BAND, LOW_LAT, 1024 * 1024);
  }

  private void lowBandHighLatency() {
    resetServer(REGULAR_WINDOW);
    doStream(LOW_BAND, LOW_LAT, 1024 * 1024);
  }

  private void highBandLowLatency() {
    resetServer(REGULAR_WINDOW);
    doStream(HIGH_BAND, LOW_LAT, 1024 * 1024);
  }

  private void highBandHighLatency() {
    resetServer(REGULAR_WINDOW);
    doStream(HIGH_BAND, HIGH_LAT, 1024 * 1024);
  }

  private void verySmallWindow() {
    resetServer(TINY_WINDOW);
    doStream(MED_BAND, MED_LAT, 1024 * 1024);
  }

  private void somethingWithAlotOfStreams() {
    // TODO
  }

  private void somethingThatGetsToMaxWindow() {
    // TODO
  }

  private void somethingThatNeverCausesFlowControlUpdates() {
    // TODO
  }

  /**
   * Applies a qdisc with rate bandwidth and delay latency, and creates a stream request with a
   * response of size 'streamSize'.
   *
   * @param bandwidth (Kbit/s)
   * @param latency (miliseconds)
   * @param streamSize (bytes)
   */
  private void doStream(int bandwidth, int latency, int streamSize){
    setBandwidth(bandwidth, latency);
    ArrayList<Float> streamingTimes = new ArrayList<Float>();
    int payloadSize = streamSize;

    ByteString body = ByteString.copyFrom(new byte[payloadSize]);
    Payload payload = Payload.newBuilder().setBody(body).build();

    for (int i = 0; i < 5; i++) {
      StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
      StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
          .addResponseParameters(ResponseParameters.newBuilder().setSize(payloadSize)).build();

      TestServiceGrpc.TestService stub = TestServiceGrpc.newStub(channel);
      long start = clock.millis();
      stub.streamingOutputCall(request, recorder);
      try {
        recorder.awaitCompletion();
      } catch (Exception e) {
        e.printStackTrace();
      }
      long end = clock.millis();
      float elapsedTime = end - start;
      streamingTimes.add(elapsedTime);
    }

    double averageStreamTime = 0;
    for (int i = 0; i < streamingTimes.size(); i++) {
      averageStreamTime += streamingTimes.get(i) * .001;
    }
    averageStreamTime /= streamingTimes.size();
    double bwithUsed = streamSize / averageStreamTime;
    System.out.println("Bandwidth Used: " + bwithUsed + " Bytes/s");
    System.out.println("% Saturated: " + (bwithUsed / ((bandwidth / 8) * 1000)) * 100);
  }

  private void resetServer(int flowControlWindow) {
    stopStaticServer();
    startStaticServer(NettyServerBuilder.forAddress(new InetSocketAddress("127.127.127.127", port))
        .flowControlWindow(flowControlWindow));
  }

  /**
   * Set netem rate in kbits and delay in ms NOTE: Had to set UID on tc (chmod 4755 /sbin/tc) to run
   * without sudo
   *
   * @param bandwidth
   */
  private void setBandwidth(int bandwidth, int delay) {
    Process tc;
    try {
      tc = Runtime.getRuntime().exec("tc qdisc del dev lo root");
      tc.waitFor();
      tc = Runtime.getRuntime().exec("tc qdisc add dev lo root handle 1: prio");
      tc.waitFor();
      tc = Runtime.getRuntime().exec("tc qdisc add dev lo parent 1:1 handle 2: netem rate "
          + bandwidth + "kbit delay " + delay + "ms");
      tc.waitFor();
      tc = Runtime.getRuntime().exec(
          "tc filter add dev lo parent 1:0 protocol ip prio 1 u32 match ip dst 127.127.127.127 flowid 2:1");
      tc.waitFor();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void main(String[] args) {
    FlowControlTest ft = new FlowControlTest();
    ft.setUp();
    ft.doTests();
  }

  @Override
  protected ManagedChannel createChannel() {
    // TODO(mcripps): Auto-generated method stub
    return null;
  }

}



