package example;

import flowsim.Client;
import flowsim.Connection;
import flowsim.Simulation;
import flowsim.SimulationBuilder;
import flowsim.Stream;

import java.util.ArrayList;
import java.util.List;

// incomplete right now

public class Example {

  static Simulation sim;

  public class AdvPool {
    int available = 500 * 1024 * 1024;
    List<Request> queue = new ArrayList<Request>();
    List<Stream> streams = new ArrayList<Stream>();

    public void addStream(Stream s) {
      streams.add(s);
    }

    public void request() {

    }



    private class Request {
      int size;
      int when;

      public Request(int size, int when) {
        this.size = size;
        this.when = when;
      }
    }
  }

  static class myConnection extends Connection {
    @Override
    public void connectionOnData() {
      // TODO(mcripps): Auto-generated method stub

    }

    @Override
    public void connectionOnHeader() {
      // TODO(mcripps): Auto-generated method stub

    }

    @Override
    public void connectionOnTick() {
      // TODO(mcripps): Auto-generated method stub
      sim.printStatistics();
      System.out.println("---------");

    }

    @Override
    public Connection getInstance() {
      return new myConnection();
    }

  }

  static class coreStream extends Stream {

    @Override
    public void streamOnData(int size) {
      // TODO(mcripps): Auto-generated method stub

    }

    @Override
    public void streamOnHeader(int size) {
      incrementWindow(streamTime(), 5 + size);
    }

    @Override
    public void streamOnTick() {
    }

    @Override
    public Stream getInstance() {
      return new coreStream();
    }

  }

  public static void main(String[] args) throws InterruptedException {
    Example x = new Example();
    sim =
        new SimulationBuilder().setBandwidth(1 * 1024).setDuration(500).setLatency(1)
            .setWindow(1 * 1024).build();
    sim.addConnection(new myConnection(), 1);
    sim.addStream(new coreStream(), 1);
    sim.addClient(1, 1 * 1024);
    sim.addApplication(1, 16 * 1024);
    sim.run();
    sim.printStatistics();
  }
}
