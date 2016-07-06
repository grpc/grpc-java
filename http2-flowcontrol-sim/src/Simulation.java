import java.util.ArrayList;

public class Simulation {
  private int howLong;
  private SimulationClock clock;
  private ArrayList<Connection> connections;

  /**
   * Return a new simulation with the requested number of streams and connections all listening to
   * the same simulation clock. Parameters window, latency, and bandwidth are used to set the
   * initial window size, latency and bandwidth of new connections. The clock runs for 'howLong'
   * ticks.
   *
   * @param window
   * @param latency
   * @param bandwidth
   * @param numConnections
   * @param numStreams
   * @param howLong
   */
  public Simulation(int window, int latency, int bandwidth, int numConnections, int numStreams,
      int howLong) {
    this.howLong = howLong;
    clock = new SimulationClock();
    connections = new ArrayList<Connection>();
    for (int i = 0; i < numConnections; i++) {
      Connection c = new Connection(clock, window, latency, bandwidth);
      for (int j = 0; j < numStreams; j++) {
        Stream s = new Stream(c, clock);
        c.addStream(s);
      }
      connections.add(c);
    }
  }

  /**
   * Run the clock fot howLong ticks.
   * 
   * @throws InterruptedException
   */
  public void run() throws InterruptedException {
    clock.Run(howLong);
  }

  /**
   * Add an action to be executed by the clock on a clock tick.
   * 
   * @param delay
   * @param action
   */
  public void addSystemTickAction(int delay, ClockExecutable action) {
    clock.addTickAction(delay, action);
  }

  /**
   * Add an action to be executed by connections on a clock tick.
   * 
   * @param delay
   * @param action
   */
  public void addConnectionTickAction(int delay, ClockExecutable action) {
    for (Connection c : connections) {
      c.addTickAction(delay, action);
    }
  }

  /**
   * Add an action to be executed by streams on a clock tick.
   * 
   * @param delay
   * @param action
   */
  public void addStreamTickAction(int delay, ClockExecutable action) {
    for (Connection c : connections) {
      for (Stream s : c.streams().values()) {
        s.addTickAction(delay, action);
      }
    }
  }

  /**
   * TODO
   */
  public void addStreamOnMessageAction(ClockExecutable action) {

  }

  /**
   * TODO
   */
  public void addStreamOnHeaderAction(ClockExecutable action) {

  }
}
