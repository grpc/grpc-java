import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class SimulationClock extends SimulationListener {
  private int currentTime;
  private HashMap<Integer, List<ClockExecutable>> eventQueue;
  private Set<ClockListener> listeners;



  public SimulationClock() {
    currentTime = 0;
    eventQueue = new HashMap<Integer, List<ClockExecutable>>();
    listeners = new HashSet<ClockListener>();
  }

  public int currentTime() {
    return currentTime;
  }

  /**
   * Note: Events are executed one time step past their scheduled time to ensure that all events for
   * a particular time step have been registered.
   *
   * @param howLong
   * @throws InterruptedException
   */
  public void Run(int howLong) throws InterruptedException {
    for (int i = 0; i < howLong + 40; i++) {
      if (i < howLong) {
        tick(i);
      }
      if (i > 0) {
        if (eventQueue.get(i - 1) != null) {
          for (ClockExecutable e : eventQueue.get(i - 1)) {
          e.execute(i);
        }
        }
      }
    }
  }

  public void addEvent(int timeStep, ClockExecutable event) {
    ArrayList<ClockExecutable> eventlist;
    if (!eventQueue.containsKey(timeStep)) {
      eventlist = new ArrayList<ClockExecutable>();
    } else {
      eventlist = (ArrayList<ClockExecutable>) eventQueue.get(timeStep);
    }
    eventlist.add(event);
    eventQueue.put(timeStep, eventlist);
  }

  public void addListener(ClockListener l) {
    listeners.add(l);
  }

  public void tick(int time) {
    advanceClock();
    for (ClockListener l : listeners) {
      l.tick(time);
    }
    doTickActions(this);
  }

  private void advanceClock() {
    currentTime++;
  }
}

interface ClockExecutable {
  public void execute(int t);
}

class ClockEvent {
  int delay;
  ClockExecutable action;

  public ClockEvent(int delay, ClockExecutable action) {
    this.delay = delay;
    this.action = action;
  }
}

