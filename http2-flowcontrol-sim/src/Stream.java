import java.util.ArrayList;
import java.util.Random;

public class Stream extends SimulationListener implements ClockListener {
  private int initialWindow;
  private int windowAvailable;
  private int publishedWindow;
  private SimulationClock controller;
  private int id;
  private ArrayList<Message> sendQueue;
  private ClockExecutable sendState;
  private Connection connection;
  private int messagesRecieved;
  private int bufferedBytes;

  public Stream(Connection con, SimulationClock clock) {
    controller = clock;
    controller.addListener(this);
    sendQueue = new ArrayList<Message>();
    sendState = (int t) -> waitForSend(t);
    connection = con;
    messagesRecieved = 0;
    windowAvailable = connection.availableWindow();
    initialWindow = windowAvailable;
    bufferedBytes = 0;
    id = (new Random()).nextInt();

  }

  @Override
  public void tick(int t) {
    sendState.execute(t);
    System.out.println(this);
    controller.addEvent(t + connection.latency(), (int time) -> refillWindow());
    doTickActions(controller);
  }

  @Override
  public String toString() {
    return ("Stream " + id + ": Buffered Bytes " + bufferedBytes + ", Bytes Recieved "
        + messagesRecieved + ", Window Available: " + windowAvailable);
  }
  public int id() {
    return id;
  }

  public int initialWindow() {
    return initialWindow;
  }

  public int publishedWindow() {
    return publishedWindow;
  }

  public int messagesRecieved() {
    return messagesRecieved;
  }

  public int bufferedBytes() {
    return bufferedBytes;
  }

  /**
   * Increment the window of this stream. This is done by updating the initial window and inflating
   * the flow control window by the delta. Additionally, keep track of the the window that the
   * remote end point has. This value gets update latency steps behind the local value.
   *
   * @param clockTime
   * @param delta
   */
  public void incrementWindow(int clockTime, int delta) {
    initialWindow += delta;
    windowAvailable += delta;
    controller.addEvent(clockTime + connection.latency(), (int t) -> doWindowUpdate(delta));
  }

  /**
   * Inflate the available window. This is a "one time" increment, it is not reflected in the
   * initial window size.
   *
   * @param clockTime
   * @param delta
   */
  public void incrementAvailableWindow(int clockTime, int delta) {
    windowAvailable += delta;
    controller.addEvent(clockTime + connection.latency(), (int t) -> doWindowUpdate(delta));
  }

  /**
   * Set the initial window of this stream. Next time the window is refilled, it will inflate to the
   * new value.
   *
   * @param clockTime
   * @param windowSize
   */
  public void setInitialWindow(int clockTime, int windowSize) {
    int delta = initialWindow - windowSize;
    incrementWindow(clockTime, delta);
  }

  /**
   * Send a message of size n on this stream, with the specified callback
   *
   * @param <T>
   */
  public <T> void sendMessage(int size, ClockExecutable callback) {
    sendQueue.add(new Message(size, callback));
    updateBytes(size);
  }


  private void doWindowUpdate(int delta) {
    publishedWindow += delta;
  }

  private void updateBytes(int delta) {
    bufferedBytes += delta;
    bufferedBytes = Math.max(0, bufferedBytes);
  }

  private void writeHeader(int clockTime, int size) {
    int minStream = Math.min(size, windowAvailable);
    int minConnection = Math.min(connection.availableWindow(), connection.bandwidthAvailable());
    int sendAmt = Math.max(Math.min(minStream, minConnection), 0);
    if (sendAmt == 0) {
      return;
    }
    controller.addEvent(clockTime + connection.latency(), (int t) -> windowAvailable -= sendAmt);
    controller.addEvent(clockTime + connection.latency(), (int t) -> doWindowUpdate(-sendAmt));
    connection.consumeWindow(clockTime, sendAmt);
    int notSent = size - sendAmt;
    if (notSent == 0) {
      int messageLen = sendQueue.get(0).size;
      controller.addEvent(clockTime + connection.latency(),
          (int t) -> recieveHeader(t, messageLen));
      doSend(clockTime, (int t) -> writeMessage(t, messageLen));
    } else {
      doSend(clockTime, (int t) -> writeHeader(t, notSent));
    }
  }

  private void writeMessage(int clockTime, int size) {
    int minStream = Math.min(size, windowAvailable);
    int minConnection = Math.min(connection.availableWindow(), connection.bandwidthAvailable());
    int sendAmt = Math.min(minStream, minConnection);
    if (sendAmt == 0) {
      return;
    }
    controller.addEvent(clockTime + connection.latency(), (int t) -> windowAvailable -= sendAmt);
    controller.addEvent(clockTime + connection.latency(), (int t) -> doWindowUpdate(-sendAmt));
    connection.consumeWindow(clockTime, sendAmt);
    controller.addEvent(clockTime + connection.latency(), (int t) -> updateBytes(-sendAmt));
    int notSent = size - sendAmt;
    controller.addEvent(clockTime + connection.latency(), (int t) -> recieveMessage(t, sendAmt));
    if (notSent == 0) {
      controller.addEvent(clockTime, sendQueue.get(0).callback);
      sendQueue.remove(0);
      doSend(clockTime, (int t) -> waitForSend(t));
    } else {
      Message newMessage = new Message(notSent, sendQueue.get(0).callback);
      sendQueue.set(0, newMessage);
    }
  }

  private void recieveHeader(int clockTime, int size) {
    return;
  }

  private void recieveMessage(int clockTime, int size) {
    messagesRecieved += size;
    return;
  }

  private void waitForSend(int clockTime) {
    if (sendQueue.isEmpty()) {
      return;
    }
    doSend(clockTime, (int t) -> writeHeader(t, 5));
  }

  private void doSend(int clockTime, ClockExecutable sendType) {
    sendState = sendType;
    sendType.execute(clockTime);

  }

  private void refillWindow() {
    if ((double) windowAvailable / (double) initialWindow < .5) {
      windowAvailable = initialWindow;
    }
  }

  private class Message {
    public int size;
    public ClockExecutable callback;

    public Message(int n, ClockExecutable c) {
      size = n;
      callback = c;
    }
  }
}
