package io.grpc.examples.helloworld;

import io.grpc.Channel;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.Map;
import java.util.HashMap;

// Part 3 TCP imports (no new files, implement TCP client in this file)
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

// interactive imports
import java.util.Scanner;

public class HelloWorldClient {
  private static final Logger logger = Logger.getLogger(HelloWorldClient.class.getName());

  private final String tcpHost;
  private final int tcpPort;

  private final RestaurantGrpc.RestaurantBlockingStub restaurantStub;

  private String tcpToken = "";
  private String tcpRole = "";
  private String tcpOrderId = "";
  private String tcpTicketId = "";

  /** Construct client for accessing server using the existing channel. */
  public HelloWorldClient(Channel channel, String tcpHost, int tcpPort) {
    restaurantStub = RestaurantGrpc.newBlockingStub(channel);
    this.tcpHost = tcpHost;
    this.tcpPort = tcpPort;
  }

  public void runInteractive() {
    Scanner sc = new Scanner(System.in);

    while (true) {
      System.out.println();
      System.out.println("Interactive Restaurant Client");
      System.out.println("1) TCP Interactive");
      System.out.println("0) Exit");
      System.out.print("> ");

      String choice = sc.nextLine().trim();
      if (choice.equals("0")) break;

      if (choice.equals("1")) {
        try {
          tcpInteractive(sc);
        } catch (Exception e) {
          System.out.println("[TCP] Error: " + e.getMessage());
        }
      } else {
        System.out.println("Invalid option.");
      }
    }

    System.out.println("Goodbye.");
  }

  // TCP Interactive

  private void tcpInteractive(Scanner sc) throws Exception {
    String host = tcpHost;
    int port = tcpPort;

    // reset session state each time you enter interactive TCP
    tcpToken = "";
    tcpRole = "";
    tcpOrderId = "";
    tcpTicketId = "";

    try (TcpClient c = new TcpClient(host, port)) {
      System.out.println("[TCP] " + c.readLine()); // CONNECTED

      while (true) {
        System.out.println();
        System.out.println("------ MENU ------");
        System.out.println("Session: token=" + (tcpToken.isEmpty() ? "(none)" : tcpToken)
            + " role=" + (tcpRole.isEmpty() ? "(none)" : tcpRole)
            + " orderId=" + (tcpOrderId.isEmpty() ? "(none)" : tcpOrderId)
            + " ticketId=" + (tcpTicketId.isEmpty() ? "(none)" : tcpTicketId));
        System.out.println("1) Login");
        System.out.println("2) Logout");
        System.out.println("3) List Menu");
        System.out.println("4) Create Order");
        System.out.println("5) Add Item");
        System.out.println("6) Submit Order");
        System.out.println("7) Get Tickets");
        System.out.println("8) Ack Ticket");
        System.out.println("9) Get Bill");
        System.out.println("10) Update Menu Item (MANAGER)");
        System.out.println("0) Back");
        System.out.print("> ");

        String cmd = sc.nextLine().trim();
        if (cmd.equals("0")) {
          c.sendLine("QUIT|");
          System.out.println("[TCP] " + c.readLine());
          return;
        }

        switch (cmd) {
          case "1": tcpLoginInteractive(sc, c); break;
          case "2": tcpLogoutInteractive(c); break;
          case "3": tcpListMenuInteractive(c); break;
          case "4": tcpCreateOrderInteractive(sc, c); break;
          case "5": tcpAddItemInteractive(sc, c); break;
          case "6": tcpSubmitOrderInteractive(c); break;
          case "7": tcpGetTicketsInteractive(sc, c); break;
          case "8": tcpAckTicketInteractive(sc, c); break;
          case "9": tcpGetBillInteractive(c); break;
          case "10": tcpUpdateMenuItemInteractive(sc, c); break;
          default: System.out.println("Invalid option."); break;
        }
      }
    }
  }

  private void tcpLoginInteractive(Scanner sc, TcpClient c) throws Exception {
    System.out.print("username (manager/server/chef): ");
    String u = sc.nextLine().trim();
    System.out.print("password: ");
    String pass = sc.nextLine().trim();

    c.sendLine("LOGIN|username=" + u + ";password=" + pass);
    String r = c.readLine();
    System.out.println("[TCP] " + r);

    tcpToken = extract(r, "token");
    tcpRole = extract(r, "role");
    tcpOrderId = "";
    tcpTicketId = "";
  }

  private void tcpLogoutInteractive(TcpClient c) throws Exception {
    if (tcpToken.isEmpty()) { System.out.println("Not logged in."); return; }
    c.sendLine("LOGOUT|token=" + tcpToken);
    String r = c.readLine();
    System.out.println("[TCP] " + r);
    tcpToken = "";
    tcpRole = "";
    tcpOrderId = "";
    tcpTicketId = "";
  }

  private void tcpUpdateMenuItemInteractive(Scanner sc, TcpClient c) throws Exception {
    if (tcpToken.isEmpty()) { System.out.println("Login first."); return; }
    if (!"MANAGER".equalsIgnoreCase(tcpRole)) {
      System.out.println("Only MANAGER can update menu.");
      return;
    }

    System.out.print("itemId: ");
    String itemId = sc.nextLine().trim();

    System.out.print("name (blank=no change): ");
    String name = sc.nextLine().trim();

    System.out.print("category STARTER/MAIN/DESSERT/DRINK (blank=no change): ");
    String cat = sc.nextLine().trim();

    Long newPriceCents = null;
    System.out.print("change price? (y/N): ");
    String changePrice = sc.nextLine().trim();
    if (changePrice.equalsIgnoreCase("y") || changePrice.equalsIgnoreCase("yes")) {
      long dollars = readLong(sc, "dollars (>=0): ", 0, Long.MAX_VALUE);
      long cents   = readLong(sc, "cents (0-99): ", 0, 99);
      newPriceCents = dollars * 100 + cents;
    }

    System.out.print("active true/false (blank=no change): ");
    String active = sc.nextLine().trim();

    StringBuilder sb = new StringBuilder();
    sb.append("UPDATE_MENU_ITEM|token=").append(tcpToken)
      .append(";itemId=").append(itemId);

    if (!name.isEmpty()) sb.append(";name=").append(name);
    if (!cat.isEmpty()) sb.append(";category=").append(cat);
    if (newPriceCents != null) sb.append(";price=").append(newPriceCents);
    if (!active.isEmpty()) sb.append(";active=").append(active);

    c.sendLine(sb.toString());
    System.out.println("[TCP] " + c.readLine());
  }

  private void tcpListMenuInteractive(TcpClient c) throws Exception {
    if (tcpToken.isEmpty()) { System.out.println("Login first."); return; }

    c.sendLine("LIST_MENU|token=" + tcpToken);
    String r = c.readLine();

    if (r == null) {
      System.out.println("[TCP] No response.");
      return;
    }
    if (!r.startsWith("OK|")) {
      System.out.println("[TCP] " + r);
      return;
    }

    String itemsPart = extract(r, "items");
    if (itemsPart.isEmpty()) {
      System.out.println("[TCP] Menu empty.");
      return;
    }

    class TcpMenuItem {
      String id, name, cat;
      long cents;
      TcpMenuItem(String id, String name, String cat, long cents) {
        this.id = id; this.name = name; this.cat = cat; this.cents = cents;
      }
    }

    java.util.Map<String, java.util.List<TcpMenuItem>> byCat = new java.util.HashMap<>();
    long maxId = "ITEM ID".length();
    long maxName = "NAME".length();

    String[] items = itemsPart.split(",");
    for (String item : items) {
      String[] p = item.split("\\^");
      if (p.length != 4) continue;

      String id = p[0].trim();
      String name = p[1].trim();
      String cat = p[2].trim();
      long cents = 0;
      try { cents = Long.parseLong(p[3].trim()); } catch (Exception ignored) {}

      byCat.computeIfAbsent(cat, k -> new java.util.ArrayList<>())
           .add(new TcpMenuItem(id, name, cat, cents));

      if (id.length() > maxId) maxId = id.length();
      if (name.length() > maxName) maxName = name.length();
    }

    String[] order = new String[] { "STARTER", "MAIN", "DESSERT", "DRINK" };

    System.out.println();
    System.out.println("========== TCP MENU ==========");

    for (String cat : order) {
      java.util.List<TcpMenuItem> list = byCat.get(cat);
      if (list == null || list.isEmpty()) continue;

      list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

      System.out.println();
      System.out.println(cat);
      System.out.println(repeat('=', cat.length()));

      System.out.printf("  [%-" + (int)maxId + "s]  %-" + (int)maxName + "s  %s%n",
          "ITEM ID", "NAME", "PRICE");
      System.out.println("  " + repeat('-', (int)(maxId + 2))
          + "  " + repeat('-', (int)maxName)
          + "  " + repeat('-', 10));

      for (TcpMenuItem mi : list) {
        String price = centsToMoney(mi.cents);
        System.out.printf("  [%-" + (int)maxId + "s]  %-" + (int)maxName + "s  %10s%n",
            mi.id, mi.name, price);
      }
    }

    System.out.println();
  }

  private static String centsToMoney(long cents) {
    long dollars = cents / 100;
    long rem = Math.abs(cents % 100);
    return "$" + dollars + "." + (rem < 10 ? "0" + rem : "" + rem);
  }

  private void tcpCreateOrderInteractive(Scanner sc, TcpClient c) throws Exception {
    if (tcpToken.isEmpty()) { System.out.println("Login first."); return; }

    System.out.print("Type (1=dine-in, 2=takeout): ");
    String t = sc.nextLine().trim();

    if (t.equals("1")) {
      System.out.print("table (1-50): ");
      String table = sc.nextLine().trim();
      System.out.print("guests (1-20): ");
      String guests = sc.nextLine().trim();

      c.sendLine("CREATE_ORDER|token=" + tcpToken + ";type=DINE_IN;table=" + table + ";guests=" + guests);
    } else if (t.equals("2")) {
      System.out.print("guest name: ");
      String name = sc.nextLine().trim();
      c.sendLine("CREATE_ORDER|token=" + tcpToken + ";type=TAKEOUT;guest=" + name);
    } else {
      System.out.println("Invalid type.");
      return;
    }

    String r = c.readLine();
    System.out.println("[TCP] " + r);
    tcpOrderId = extract(r, "orderId");
    tcpTicketId = "";
  }

  private void tcpAddItemInteractive(Scanner sc, TcpClient c) throws Exception {
    if (tcpToken.isEmpty()) { System.out.println("Login first."); return; }
    if (tcpOrderId.isEmpty()) { System.out.println("Create an order first."); return; }

    System.out.print("itemId: ");
    String itemId = sc.nextLine().trim();
    System.out.print("qty: ");
    String qty = sc.nextLine().trim();

    c.sendLine("ADD_ITEM|token=" + tcpToken + ";orderId=" + tcpOrderId + ";itemId=" + itemId + ";qty=" + qty);
    System.out.println("[TCP] " + c.readLine());
  }

  private void tcpSubmitOrderInteractive(TcpClient c) throws Exception {
    if (tcpToken.isEmpty()) { System.out.println("Login first."); return; }
    if (tcpOrderId.isEmpty()) { System.out.println("Create an order first."); return; }

    c.sendLine("SUBMIT_ORDER|token=" + tcpToken + ";orderId=" + tcpOrderId);
    String r = c.readLine();
    System.out.println("[TCP] " + r);
    tcpTicketId = extract(r, "ticketId");
  }

  private void tcpGetTicketsInteractive(Scanner sc, TcpClient c) throws Exception {
    if (tcpToken.isEmpty()) { System.out.println("Login first."); return; }

    System.out.print("status (TICKET_SENT / TICKET_ACKNOWLEDGED): ");
    String s = sc.nextLine().trim();
    if (s.isEmpty()) s = "TICKET_SENT";

    c.sendLine("GET_TICKETS|token=" + tcpToken + ";status=" + s);
    System.out.println("[TCP] " + c.readLine());
  }

  private void tcpAckTicketInteractive(Scanner sc, TcpClient c) throws Exception {
    if (tcpToken.isEmpty()) { System.out.println("Login first."); return; }

    System.out.print("ticketId (blank to use last=" + (tcpTicketId.isEmpty() ? "none" : tcpTicketId) + "): ");
    String t = sc.nextLine().trim();
    if (t.isEmpty()) t = tcpTicketId;
    if (t.isEmpty()) { System.out.println("No ticketId available."); return; }

    c.sendLine("ACK_TICKET|token=" + tcpToken + ";ticketId=" + t);
    System.out.println("[TCP] " + c.readLine());
  }

  private void tcpGetBillInteractive(TcpClient c) throws Exception {
    if (tcpToken.isEmpty()) { System.out.println("Login first."); return; }
    if (tcpOrderId.isEmpty()) { System.out.println("No orderId in session."); return; }

    c.sendLine("GET_BILL|token=" + tcpToken + ";orderId=" + tcpOrderId);
    String r = c.readLine();
    if (r == null) {
      System.out.println("[TCP] (no response)");
      return;
    }

    printTcpBill(r);
  }

  private static void printTcpBill(String line) {
    if (!line.startsWith("OK|")) {
      System.out.println("[TCP] " + line);
      return;
    }

    Map<String, String> kv = parseTcpKv(line);

    String orderId  = kv.getOrDefault("orderId", "(unknown)");
    String subtotal = kv.getOrDefault("subtotal", "(unknown)");
    String total    = kv.getOrDefault("total", "(unknown)");
    String lines    = kv.getOrDefault("lines", "");

    System.out.println();
    System.out.println("      BILL      ");
    System.out.println("Order:    " + orderId);

    if (lines.trim().isEmpty()) {
      System.out.println("(no line items)");
    } else {
      int nameW = "ITEM".length();
      int qtyW  = "QTY".length();
      int amtW  = "AMOUNT".length();

      String[] items = lines.split(",");
      for (String it : items) {
        String[] f = it.split("\\^");
        if (f.length < 4) continue;
        String name = f[1];
        String qty  = f[2];
        String amt  = centsToMoneyStringSafe(f[3]);
        nameW = Math.max(nameW, name.length());
        qtyW  = Math.max(qtyW, qty.length());
        amtW  = Math.max(amtW, amt.length());
      }

      System.out.printf("%-" + nameW + "s  %"
          + qtyW + "s  %"
          + amtW + "s%n", "ITEM", "QTY", "AMOUNT");
      System.out.println(repeat('-', nameW) + "  " + repeat('-', qtyW) + "  " + repeat('-', amtW));

      for (String it : items) {
        String[] f = it.split("\\^");
        if (f.length < 4) continue;

        String name = f[1];
        String qty  = f[2];
        String amt  = centsToMoneyStringSafe(f[3]);

        System.out.printf("%-" + nameW + "s  %"
            + qtyW + "s  %"
            + amtW + "s%n", name, qty, amt);
      }
    }

    System.out.println("Subtotal: " + subtotal);
    System.out.println("Total:    " + total);
    System.out.println();
  }

  private static Map<String, String> parseTcpKv(String line) {
    Map<String, String> map = new HashMap<>();
    int pipe = line.indexOf('|');
    if (pipe < 0) return map;

    String body = line.substring(pipe + 1);
    String[] pairs = body.split(";");
    for (String p : pairs) {
      int eq = p.indexOf('=');
      if (eq < 0) continue;
      String k = p.substring(0, eq).trim();
      String v = p.substring(eq + 1).trim();
      map.put(k, v);
    }
    return map;
  }

  private static String centsToMoneyStringSafe(String centsStr) {
    long cents;
    try {
      cents = Long.parseLong(centsStr.trim());
    } catch (Exception e) {
      return centsStr;
    }
    long dollars = cents / 100;
    long rem = Math.abs(cents % 100);
    return "USD " + dollars + "." + (rem < 10 ? "0" + rem : "" + rem);
  }

  private static String repeat(char ch, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) sb.append(ch);
    return sb.toString();
  }

  private static String moneyToString(Money m) {
    long cents = m.getCents();
    long dollars = cents / 100;
    long rem = Math.abs(cents % 100);
    return m.getCurrency() + " " + dollars + "." + (rem < 10 ? "0" + rem : "" + rem);
  }

  private static void printMenu(Menu menu) {
    java.util.Map<MenuCategory, java.util.List<MenuItem>> byCat = new java.util.HashMap<>();
    for (MenuItem mi : menu.getItemsList()) {
      if (!mi.getActive()) continue;
      byCat.computeIfAbsent(mi.getCategory(), k -> new java.util.ArrayList<>()).add(mi);
    }

    MenuCategory[] order = new MenuCategory[] {
        MenuCategory.STARTER, MenuCategory.MAIN, MenuCategory.DESSERT, MenuCategory.DRINK
    };

    int idW = "ITEM ID".length();
    int nameW = "NAME".length();
    for (MenuCategory c : order) {
      java.util.List<MenuItem> items = byCat.get(c);
      if (items == null) continue;
      for (MenuItem mi : items) {
        idW = Math.max(idW, mi.getItemId().length());
        nameW = Math.max(nameW, mi.getName().length());
      }
    }

    java.util.function.BiFunction<Character, Integer, String> rep = (ch, n) -> {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < n; i++) sb.append(ch);
      return sb.toString();
    };

    String title = "RESTAURANT MENU";
    int totalW = Math.max(title.length() + 6, idW + 2 + nameW + 2 + 12 + 6);
    System.out.println();
    System.out.println("+" + rep.apply('-', totalW) + "+");
    System.out.println("|  " + title + rep.apply(' ', Math.max(0, totalW - (title.length() + 2))) + "|");
    System.out.println("+" + rep.apply('-', totalW) + "+");

    for (MenuCategory cat : order) {
      java.util.List<MenuItem> items = byCat.get(cat);
      if (items == null || items.isEmpty()) continue;

      items.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

      String catLabel = cat.name();
      System.out.println();
      System.out.println(catLabel);
      System.out.println(rep.apply('=', catLabel.length()));

      String header = String.format("  %-"+idW+"s  %-"+nameW+"s  %s", "ITEM ID", "NAME", "PRICE");
      System.out.println(header);
      System.out.println("  " + rep.apply('-', idW) + "  " + rep.apply('-', nameW) + "  " + rep.apply('-', 10));

      for (MenuItem mi : items) {
        String price = moneyToString(mi.getPrice());
        System.out.printf("  %-"+idW+"s  %-"+nameW+"s  %10s%n",
            mi.getItemId(), mi.getName(), price);
      }
    }

    System.out.println();
  }

  private static void printBill(Bill bill) {
    System.out.println("BILL for " + bill.getOrderId());
    for (OrderLineItem li : bill.getLinesList()) {
      System.out.println("  " + li.getQuantity() + "x " + li.getName()
          + " @ " + moneyToString(li.getUnitPrice())
          + " = " + moneyToString(li.getLineTotal()));
    }
    System.out.println("  Subtotal: " + moneyToString(bill.getSubtotal()));
    System.out.println("  Total:    " + moneyToString(bill.getTotal()));
  }

  private static long promptMoneyCents(Scanner sc) {
    long dollars = readLong(sc, "dollars (>=0): ", 0, Long.MAX_VALUE);
    long cents = readLong(sc, "cents (0-99): ", 0, 99);
    return dollars * 100 + cents;
  }

  private static long readLong(Scanner sc, String prompt, long min, long max) {
    while (true) {
      System.out.print(prompt);
      String s = sc.nextLine().trim();
      try {
        long v = Long.parseLong(s);
        if (v < min || v > max) {
          System.out.println("Enter a value in range [" + min + ", " + max + "].");
          continue;
        }
        return v;
      } catch (Exception e) {
        System.out.println("Enter a valid whole number.");
      }
    }
  }

  static class TcpClient implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    

    TcpClient(String host, int port) throws Exception {
      socket = new Socket(host, port);
      in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    String readLine() throws Exception { return in.readLine(); }
    void sendLine(String line) { out.println(line); }

    @Override
    public void close() throws Exception { socket.close(); }
  }

  private static String extract(String line, String key) {
    if (line == null) return "";
    int pipe = line.indexOf('|');
    if (pipe < 0) return "";
    String body = line.substring(pipe + 1);
    String[] pairs = body.split(";");
    for (String p : pairs) {
      int eq = p.indexOf('=');
      if (eq < 0) continue;
      String k = p.substring(0, eq).trim();
      String v = p.substring(eq + 1).trim();
      if (k.equals(key)) return v;
    }
    return "";
  }

  public static void main(String[] args) throws Exception {
    String grpcTarget = "localhost:50051";
    String tcpHost = "localhost";
    int tcpPort = 50052;

    if (args.length >= 1) {
      grpcTarget =args[0];
    }
    if (args.length >= 2) {
      tcpHost = args[1];
    }
    if (args.length >= 3) {
      try {
        tcpPort = Integer.parseInt(args[2]);
      } catch (Exception e) {
        System.out.println("Invalid TCP port: " + args[2] + ". Defaulting to 50052");
        tcpPort = 50052;
      }
    }

    ManagedChannel channel = Grpc.newChannelBuilder(grpcTarget, InsecureChannelCredentials.create())
        .build();
    try {
      HelloWorldClient client = new HelloWorldClient(channel, tcpHost, tcpPort);
      client.runInteractive();
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
