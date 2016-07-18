package flowsim;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

public abstract class Connection implements ClockListener {

  private HashMap<Integer, Stream> streams = new HashMap<Integer, Stream>();
  private int initialWindow;
  private int availableWindow;
  private int publishedWindow;
  private SimulationClock controller;
  private int bandwidthAvailable;
  private int latency;
  private int bandwidth;

  public abstract void connectionOnData();

  public abstract void connectionOnHeader();

  public abstract void connectionOnTick();

  public abstract Connection getInstance();

  void setParams(SimulationClock clock, int initWindow, int latency, int bandwidth) {
    controller = clock;
    controller.addListener(this);
    initialWindow = initWindow;
    availableWindow = initWindow;
    publishedWindow = initWindow;
    this.latency = latency;
    this.bandwidth = bandwidth;
    bandwidthAvailable = bandwidth;
  }

  @Override
  public void tick(int time) {
    bandwidthAvailable = bandwidth;
    connectionOnTick();
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

  public Stream getStream(int id) {
    return streams.get(id);
  }

  public Set<Integer> streams() {
    return streams.keySet();
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
    publishedWindow += (initialWindow - oldWindow);
    publishedWindow = Math.max(availableWindow, 0);
    int delta = initialWindow - oldWindow;
    controller.addEvent(timeStep + latency, new WindowVisitor(Util.AVAILABLE, delta));
    refillWindow(timeStep);
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
    availableWindow -= size;
    controller.addEvent(timeStep + latency, new WindowVisitor(Util.PUBLISHED, -size));
    refillWindow(timeStep);
  }

  /**
   * Add a new stream to this connection.
   *
   * @param s
   */
  public void addStream(Stream s) {
    streams.put(s.id(), s);
  }

  void setClock(SimulationClock clock) {
    this.controller = clock;
    clock.addListener(this);
  }

  private void refillWindow(int time) {
    int delta = initialWindow - availableWindow;
    if (availableWindow < initialWindow / 2) {
      publishedWindow = initialWindow;
      controller.addEvent(time + latency, new WindowVisitor(Util.AVAILABLE, delta));
    }
  }

  private class WindowVisitor implements Visitor {
    private String window;
    private int delta;

    public WindowVisitor(String window, int delta) {
      this.window = window;
      this.delta = delta;
    }

    @Override
    public void visit() {
      switch (window) {
        case Util.PUBLISHED:
          publishedWindow += delta;
          break;
        case Util.AVAILABLE:
          availableWindow += delta;
          break;
      }
    }
  }

}
