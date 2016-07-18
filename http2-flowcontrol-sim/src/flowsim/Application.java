package flowsim;

import java.util.ArrayList;

public class Application implements ClockListener {

  private Simulation simulation;
  private int delay;
  private int messageSize;

  public Application(Simulation simulation, int delay, int messageSize) {
    this.simulation = simulation;
    this.delay = delay;
    this.messageSize = messageSize;
  }

  private void consume() {
    ArrayList<Connection> connections = simulation.getConnections();
    for (Connection connection : connections) {
      for (int streamId : connection.streams()) {
        Stream stream = connection.getStream(streamId);
        stream.consumeRecievedBytes(messageSize);
      }
    }

  }

  @Override
  public void tick(int time) {
    if (time % delay == 0) {
      consume();
    }
  }

}
