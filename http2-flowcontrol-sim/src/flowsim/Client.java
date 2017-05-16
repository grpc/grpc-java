package flowsim;

import java.util.ArrayList;

public class Client implements ClockListener {
  Simulation simulation;
  int delay;
  int messageSize;

  public Client(Simulation simulation, int delay, int messageSize) {
    this.simulation = simulation;
    this.delay = delay;
    this.messageSize = messageSize;
  }

  private void produce() {
    ArrayList<Connection> connections = simulation.getConnections();
    for (Connection connection : connections) {
      for (int streamId : connection.streams()) {
        Stream stream = connection.getStream(streamId);
        stream.sendMessage(messageSize);
      }
    }
  }

  @Override
  public void tick(int time) {
    if (time % delay == 0) {
      produce();
    }
  }
}
