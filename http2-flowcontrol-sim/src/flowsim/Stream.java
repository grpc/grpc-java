package flowsim;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public abstract class Stream implements ClockListener {
  private int initialWindow;
  private int windowAvailable;
  private int publishedWindow;
  private SimulationClock controller;
  private int id;
  private List<Message> sendQueue = new ArrayList<Message>();
  private Connection connection;
  private int messagesRecieved;
  private int sendBufferedBytes;
  private int rcvBufferedBytes;
  private int currentTick;

  public abstract void streamOnData(int length);

  public abstract void streamOnHeader(int length);

  public abstract void streamOnTick();

  public abstract Stream getInstance();

  void setParams(Connection cnxn, SimulationClock clock) {
    controller = clock;
    controller.addListener(this);
    connection = cnxn;
    windowAvailable = connection.availableWindow();
    publishedWindow = windowAvailable;
    initialWindow = windowAvailable;
    id = (new Random()).nextInt();
  }

  @Override
  public void tick(int t) {
    currentTick = t;
    doSend(t);
    streamOnTick();
  }

  // This is just for convenience right now, will probably change to a more useful toString
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Stream ").append(id).append(": Send Buffered Bytes ")
        .append(sendBufferedBytes).append(", Recv Buffered Bytes ").append(rcvBufferedBytes)
        .append("Bytes Recieved ").append(messagesRecieved).append(", Window Available ")
        .append(windowAvailable);
    return (sb.toString());
  }

  public int streamTime() {
    return currentTick;
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

  public int sendBufferedBytes() {
    return sendBufferedBytes;
  }

  public int recvBufferedBytes() {
    return rcvBufferedBytes;
  }

  /**
   * Increment the window of this stream. This is done by updating the initial window and inflating
   * the flow control window by the delta.
   *
   * @param clockTime
   * @param delta
   */
  public void incrementWindow(int clockTime, int delta) {
    initialWindow += delta;
    publishedWindow += delta;
    controller.addEvent(clockTime + connection.latency(), new WindowVisitor(Util.AVAILABLE, delta));
    refillWindow(clockTime);
  }

  /**
   * Inflate the available window. This is a "one time" increment, it is not reflected in the
   * initial window size.
   *
   * TODO: not sure this is even necessary / is a realistic operation
   *
   * @param clockTime
   * @param delta
   */
  public void incrementAvailableWindow(int clockTime, int delta) {
    windowAvailable += delta;
    controller.addEvent(clockTime + connection.latency(), new WindowVisitor(Util.PUBLISHED, delta));
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
    refillWindow(clockTime);
  }

  /**
   * Send a message of size n on this stream, with the specified callback
   *
   * @param <T>
   */
  public <T> void sendMessage(int size) {
    sendQueue.add(new Message(Util.HEADER, Util.HEADER_SIZE));
    sendQueue.add(new Message(Util.DATA, size));
    updateSendBytes(size);
  }

  // only ever called by the application
  void consumeRecievedBytes(int size) {
    rcvBufferedBytes -= Math.min(rcvBufferedBytes, size);
  }

  private void updateSendBytes(int delta) {
    sendBufferedBytes += delta;
    sendBufferedBytes = Math.max(0, sendBufferedBytes);
  }

  private void writeData(int clockTime, int size){
    int minStream = Math.min(size, windowAvailable);
    int minConnection = Math.min(connection.availableWindow(), connection.bandwidthAvailable());
    int amt = Math.max(Math.min(minStream, minConnection), 0);
    if (amt == 0) {
      return;
    }
    int unSent = size - amt;
    Message m = sendQueue.get(0);
    if (m.messageType == Util.DATA) {
      windowAvailable -= amt;
      controller.addEvent(clockTime + connection.latency(),
          new WindowVisitor(Util.PUBLISHED, -amt));
      connection.consumeWindow(clockTime, amt);
      refillWindow(clockTime);
      sendBufferedBytes -= amt;
      controller.addEvent(clockTime + connection.latency(), new ReceiveVisitor(Util.DATA, amt));
    }
    if (unSent == 0) {
      if (m.messageType == Util.HEADER) {
        controller.addEvent(clockTime + connection.latency(),
            new ReceiveVisitor(Util.HEADER, Util.HEADER_SIZE));
      }
      sendQueue.remove(0);
    } else {
      Message newMessage = new Message(m.messageType, unSent);
      sendQueue.set(0, newMessage);
    }
    doSend(clockTime);
  }

  private void recieveHeader(int size) {
    System.out.println("recieve header");
    streamOnHeader(size);
    connection.connectionOnHeader();
  }

  private void recieveMessage(int size) {
    messagesRecieved += size;
    rcvBufferedBytes += size;
    streamOnData(size);
    connection.connectionOnData();
  }

  private void doSend(int clockTime) {
    if (sendQueue.size() > 0){
      writeData(clockTime, sendQueue.get(0).size);
    }
  }

  private void refillWindow(int clockTime) {
    int delta = initialWindow - windowAvailable;
    if (windowAvailable < initialWindow / 2) {
      publishedWindow += delta;
      controller.addEvent(clockTime + connection.latency(),
          new WindowVisitor(Util.AVAILABLE, delta));
    }
  }

  private class Message {
    public int messageType;
    public int size;

    public Message(int messageType, int size) {
      this.messageType = messageType;
      this.size = size;
    }
  }

  private class WindowVisitor implements Visitor {

    private String window;
    private int delta;

    public WindowVisitor(String window, int delta) {
      this.window = window;
      this.delta = delta;
    }

    @Override
    public void visit() {
      switch (window) {
        case Util.PUBLISHED:
          publishedWindow += delta;
          break;
        case Util.AVAILABLE:
          windowAvailable += delta;
          break;
      }
    }
  }

  private class ReceiveVisitor implements Visitor {
    int messageType;
    int amount;

    public ReceiveVisitor(int messageType, int amount) {
      this.messageType = messageType;
      this.amount = amount;
    }

    @Override
    public void visit() {
      switch (messageType) {
        case Util.HEADER:
          recieveHeader(amount);
          break;
        case Util.DATA:
          recieveMessage(amount);
          break;
      }
    }
  }
}
