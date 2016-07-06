import java.util.ArrayList;

abstract class SimulationListener {
  private ArrayList<ClockEvent> tickActions;
  private ArrayList<ClockEvent> onDataActions;
  private ArrayList<ClockEvent> onHeaderActions;

  public SimulationListener() {
    tickActions = new ArrayList<ClockEvent>();
    onDataActions = new ArrayList<ClockEvent>();
    onHeaderActions = new ArrayList<ClockEvent>();
  }

  /**
   * Add a clock executable action to be performed on tick
   */
  public void addTickAction(int delay, ClockExecutable action) {
    tickActions.add(new ClockEvent(delay, action));
  }

  /**
   * Add a clock executable action to be performed on reception of a data frame
   */
  public void addOnDataAction(int delay, ClockExecutable action) {
    onDataActions.add(new ClockEvent(delay, action));
  }

  /**
   * Add a clock executable action to be performed on reception of a header
   */
  public void addOnHeaderAction(int delay, ClockExecutable action) {
    onHeaderActions.add(new ClockEvent(delay, action));
  }


  protected void doTickActions(SimulationClock controller) {
    doActions(controller, tickActions);
  }

  protected void doOnDataActions(SimulationClock controller) {
    doActions(controller, onDataActions);
  }

  protected void doOnHeaderActions(SimulationClock controller) {
    doActions(controller, onHeaderActions);
  }

  private void doActions(SimulationClock controller, ArrayList<ClockEvent> actions) {
    for (ClockEvent event : actions) {
      controller.addEvent(controller.currentTime() + event.delay, event.action);
    }
  }

}
