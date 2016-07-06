/**
 * Example class. Creates a simulation with a 64kB connection window, 10 timestep latency, and 64 kB
 * bandwidth. There is one connection, 4 streams, and the simulation runs for 300 timesteps. This
 * example shows how to add tick actions to the clock, connection, and stream. Once actions can also
 * be added on header or on data, the idea is to be able to add more complex actions like window
 * updates etc. to 'plug in' your own flow control algorithm.
 *
 * TODO: Maybe make simulation constructor a builder because the constructor is confusing
 */
public class Example {

  public static void main(String[] args) throws InterruptedException {
    Simulation sim = new Simulation(64 * 1024, 10, 64 * 1024, 1, 4, 300);
    // add actions to clock, connection, and stream
    sim.addSystemTickAction(0, (ClockExecutable) (int t) -> System.out.println("clock tick"));
    sim.addConnectionTickAction(0,
        (ClockExecutable) (int t) -> System.out.println("connection tick"));
    sim.addStreamTickAction(0,
        (ClockExecutable) (int t) -> System.out.println("stream tick"));
    // Start the simulation
    sim.run();

  }
}
