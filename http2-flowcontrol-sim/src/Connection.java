import java.util.ArrayList;
import java.util.HashMap;

public class Connection extends SimulationListener implements ClockListener {

  private HashMap<Integer, Stream> streams;
  private int initialWindow;
  private int availableWindow;
  private int publishedWindow;
  private SimulationClock controller;
  private int bandwidthAvailable;
  private int latency;
  private int bandwidth;

  public Connection(SimulationClock clock, int initWindow, int latency, int bandwidth) {
    streams = new HashMap<Integer, Stream>();
    controller = clock;
    controller.addListener(this);
    initialWindow = initWindow;
    availableWindow = initWindow;
    this.latency = latency;
    this.bandwidth = bandwidth;
    bandwidthAvailable = this.bandwidth;
  }

  @Override
  public void tick(int time) {
    bandwidthAvailable = bandwidth;
    refillWindow();
    doTickActions(controller);
  }

  public int intialWindow() {
    return initialWindow;
  }

  public int availableWindow() {
    return availableWindow;
  }

  public int publishedWindow() {
    return publishedWindow;
  }

  public int bandwidthAvailable() {
    return bandwidthAvailable;
  }

  public int latency() {
    return latency;
  }

  public HashMap<Integer, Stream> streams() {
    return streams;
  }

  /**
   * Increment the connection-wide initial window. Does not update the individual stream windows
   *
   * @param timeStep
   * @param newWindow
   */
  public void initialWindow(int timeStep, int newWindow) {
    int oldWindow = initialWindow;
    initialWindow = Math.max(0, newWindow);
    availableWindow += (initialWindow - oldWindow);
    // schedule the published window to be updated in Latency steps:
    controller.addEvent(timeStep + latency, ((int t) -> doWindowUpdate(initialWindow - oldWindow)));
  }

  /**
   * Increment the flow control window of stream with id streamId.
   *
   * @param timeStep
   * @param streamId
   * @param deltaWindow
   */
  public void incrementWindow(int timeStep, int streamId, int deltaWindow) {
    if (streamId == 0) {
      initialWindow(timeStep, initialWindow + deltaWindow);
      return;
    }
    Stream s = streams.get(streamId);
    s.incrementWindow(timeStep, deltaWindow);
  }

  /**
   * Reduce the available window by size. Called when a stream sends a message to reflect a single
   * stream window change on the connection.
   *
   * @param timeStep
   * @param size
   */
  public void consumeWindow(int timeStep, int size) {
    controller.addEvent(timeStep + latency, (int t) -> availableWindow -= size);
    controller.addEvent(timeStep + latency, (int t) -> doWindowUpdate(-size));
  }

  /**
   * Add a new stream to this connection.
   *
   * @param s
   */
  public void addStream(Stream s) {
    streams.put(s.id(), s);
  }

  private void doWindowUpdate(int delta) {
    publishedWindow += delta;
  }

  private void refillWindow() {
    if ((double) availableWindow / (double) initialWindow < .5) {
      availableWindow = initialWindow;
    }
  }


}
