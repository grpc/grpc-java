package flowsim;
import java.util.ArrayList;
import java.util.List;

public class Simulation {

  private SimulationClock clock = new SimulationClock();
  private List<Connection> connections = new ArrayList<Connection>();
  private int howLong;
  private int flowControlWindow;
  private int latency;
  private int bandwidth;

  Simulation(int window, int latency, int bandwidth, int duration) {
    flowControlWindow = window;
    howLong = duration;
    this.latency = latency;
    this.bandwidth = bandwidth;
  }

  /** Get a list of all connections */
  public ArrayList<Connection> getConnections() {
    return new ArrayList<Connection>(connections);
  }

  /**
   * Add a client that produces messages of messageSize every delay clock tick
   *
   * @param delay
   * @param messageSize
   */
  public void addClient(int delay, int messageSize) {
    clock.addListener(new Client(this, delay, messageSize));
  }

  /**
   * Add an application that consumes messages of messageSize every delay clock tick
   *
   * @param delay
   * @param messageSize
   */
  public void addApplication(int delay, int messageSize) {
    clock.addListener(new Application(this, delay, messageSize));
  }

  /** Add numConnections connections to this simulation */
  public void addConnection(Connection connection, int numConnections) {
    for (int i = 0; i < numConnections; i++) {
      Connection c = connection.getInstance();
      c.setParams(clock, flowControlWindow, latency, bandwidth);
      connections.add(c);
    }
  }

  /**
   * Add numStreams streams to each connection
   */
  public void addStream(Stream stream, int numStreams) {
    assert (connections.size() != 0);
    for (Connection c : connections) {
      for (int i = 0; i < numStreams; i++) {
        Stream s = stream.getInstance();
        s.setParams(c, clock);
        c.addStream(s);
      }
    }
  }

  public void printStatistics() {
    int totalApplicationBuffered = 0;
    int totalClientBuffered = 0;
    int totalRecieved = 0;

    for (Connection c : connections) {
      for (int streamId : c.streams()) {
        Stream s = c.getStream(streamId);
        totalApplicationBuffered += s.recvBufferedBytes();
        totalClientBuffered += s.sendBufferedBytes();
        totalRecieved += s.messagesRecieved();
      }
    }

    System.out.println("Total Application Buffered Bytes: " + totalApplicationBuffered);
    System.out.println("Total Client Buffered Bytes: " + totalClientBuffered);
    System.out.println("Total Bytes Recieved: " + totalRecieved);
  }

  /**
   * Run the clock for howLong ticks.
   *
   * @throws InterruptedException
   */
  public void run() throws InterruptedException {
    clock.run(howLong);
  }

}
