package flowsim;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;


public class SimulationClock {
  private int currentTime;
  private HashMap<Integer, List<Visitor>> eventQueue;
  private Set<ClockListener> listeners;



  public SimulationClock() {
    currentTime = 0;
    eventQueue = new HashMap<Integer, List<Visitor>>();
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
  public void run(int howLong) throws InterruptedException {
    for (int i = 0; i < howLong; i++) {
      tick(i);
      if (i >= 0) {
        if (eventQueue.get(i) != null) {
          for (Visitor e : eventQueue.get(i)) {
            e.visit();
          }
        }
      }
    }
  }

  public void addEvent(int timeStep, Visitor event) {
    List<Visitor> eventlist;
    if (!eventQueue.containsKey(timeStep)) {
      eventlist = new ArrayList<Visitor>();
    } else {
      eventlist = eventQueue.get(timeStep);
    }
    eventlist.add(event);
    eventQueue.put(timeStep, eventlist);
  }

  public void addListener(ClockListener listener) {
    listeners.add(listener);
  }

  public void tick(int time) {
    advanceClock();
    for (ClockListener l : listeners) {
      l.tick(time);
    }
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

