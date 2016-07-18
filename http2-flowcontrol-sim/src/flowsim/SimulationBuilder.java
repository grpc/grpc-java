package flowsim;

public class SimulationBuilder {

  private int howLong = Util.DEFAULT_DURATION;
  private int flowControlWindow = Util.DEFAULT_WINDOW;
  private int latency = Util.DEFAULT_LATENCY;
  private int bandwidth = Util.DEFAULT_BANDWIDTH;


  public Simulation build() {
    return new Simulation(flowControlWindow, latency, bandwidth, howLong);
  }

  public SimulationBuilder setDuration(int numTicks) {
    this.howLong = numTicks;
    return this;
  }

  public SimulationBuilder setLatency(int numTicks) {
    this.latency = numTicks;
    return this;
  }

  public SimulationBuilder setBandwidth(int numBytes) {
    this.bandwidth = numBytes;
    return this;
  }

  public SimulationBuilder setWindow(int numBytes) {
    this.flowControlWindow = numBytes;
    return this;
  }
}
