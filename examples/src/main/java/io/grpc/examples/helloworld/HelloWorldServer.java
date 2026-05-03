package io.grpc.examples.helloworld;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

//tcp imports
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

//extension imports
import java.io.File;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.BufferedWriter;





/*
Current startup: 
from examples dir 
	./gradlew installDist
./build/install/examples/bin/hello-world-server 50051 50052 backend-1
./build/install/examples/bin/hello-world-server 50061 50062 backend-2
./build/install/examples/bin/hello-world-server 50071 50072 backend-3

java -cp "build\install\examples\lib\*" io.grpc.examples.helloworld.LoadBalancer 50100

./build/install/examples/bin/hello-world-client localhost:50051


*/

public class HelloWorldServer {
  private static final Logger logger = Logger.getLogger(HelloWorldServer.class.getName());

  private Server server;

  private final int grpcPort;
  private final int tcpPort;
  private final String serverName;

    static class SharedDataStore {
    private static final String MENU_FILE = "restaurant_menu.db";
    private static final String ORDERS_FILE = "restaurant_orders.db";
    private static final String TICKETS_FILE = "restaurant_tickets.db";

    private static final Object LOCK = new Object();

    static void ensureFilesExist() {
      synchronized (LOCK) {
        try {
          File menu = new File(MENU_FILE);
          File orders = new File(ORDERS_FILE);
          File tickets = new File(TICKETS_FILE);

          if (!menu.exists()) menu.createNewFile();
          if (!orders.exists()) orders.createNewFile();
          if (!tickets.exists()) tickets.createNewFile();
        } catch (Exception e) {
          throw new RuntimeException("Failed to initialize datastore files: " + e.getMessage());
        }
      }
    }

    static Map<String, MenuItem> loadMenu() {
      synchronized (LOCK) {
        Map<String, MenuItem> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(MENU_FILE))) {
          String line;
          while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] p = line.split("\\|", -1);
            if (p.length < 5) continue;

            String itemId = p[0];
            String name = p[1];
            MenuCategory cat = parseMenuCategory(p[2]);
            long cents = parseLongSafe(p[3]);
            boolean active = Boolean.parseBoolean(p[4]);

            MenuItem mi = MenuItem.newBuilder()
                .setItemId(itemId)
                .setName(name)
                .setCategory(cat)
                .setPrice(Money.newBuilder().setCents(cents).setCurrency("USD").build())
                .setActive(active)
                .build();

            map.put(itemId, mi);
          }
        } catch (Exception e) {
          throw new RuntimeException("Failed to load menu: " + e.getMessage());
        }
        return map;
      }
    }

    static void saveMenu(Map<String, MenuItem> menuItemsById) {
      synchronized (LOCK) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(MENU_FILE, false))) {
          for (MenuItem mi : menuItemsById.values()) {
            bw.write(mi.getItemId() + "|"
                + mi.getName() + "|"
                + mi.getCategory().name() + "|"
                + mi.getPrice().getCents() + "|"
                + mi.getActive());
            bw.newLine();
          }
        } catch (Exception e) {
          throw new RuntimeException("Failed to save menu: " + e.getMessage());
        }
      }
    }

    static Map<String, RestaurantCore.OrderRecord> loadOrders() {
      synchronized (LOCK) {
        Map<String, RestaurantCore.OrderRecord> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(ORDERS_FILE))) {
          String line;
          while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] p = line.split("\\|", -1);
            if (p.length < 8) continue;

            String orderId = p[0];
            OrderType type = parseOrderType(p[1]);
            int tableNumber = parseIntSafe(p[2]);
            int guestCount = parseIntSafe(p[3]);
            String guestName = p[4];
            OrderStatus status = parseOrderStatus(p[5]);
            String ticketId = p[6];
            String lineBlob = p[7];

            RestaurantCore.OrderRecord o =
                new RestaurantCore.OrderRecord(orderId, type, tableNumber, guestCount, guestName);
            o.status = status;
            o.ticketId = ticketId.isEmpty() ? null : ticketId;

            if (!lineBlob.isEmpty()) {
              String[] items = lineBlob.split(",");
              for (String item : items) {
                String[] f = item.split("\\^", -1);
                if (f.length < 5) continue;

                String itemId = f[0];
                String name = f[1];
                int qty = parseIntSafe(f[2]);
                long unit = parseLongSafe(f[3]);
                long total = parseLongSafe(f[4]);

                OrderLineItem li = OrderLineItem.newBuilder()
                    .setItemId(itemId)
                    .setName(name)
                    .setQuantity(qty)
                    .setUnitPrice(Money.newBuilder().setCents(unit).setCurrency("USD").build())
                    .setLineTotal(Money.newBuilder().setCents(total).setCurrency("USD").build())
                    .build();

                o.lines.add(li);
              }
            }

            map.put(orderId, o);
          }
        } catch (Exception e) {
          throw new RuntimeException("Failed to load orders: " + e.getMessage());
        }
        return map;
      }
    }

    static void saveOrders(Map<String, RestaurantCore.OrderRecord> orders) {
      synchronized (LOCK) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(ORDERS_FILE, false))) {
          for (RestaurantCore.OrderRecord o : orders.values()) {
            StringBuilder lines = new StringBuilder();
            boolean first = true;
            for (OrderLineItem li : o.lines) {
              if (!first) lines.append(",");
              first = false;
              lines.append(li.getItemId()).append("^")
                  .append(li.getName()).append("^")
                  .append(li.getQuantity()).append("^")
                  .append(li.getUnitPrice().getCents()).append("^")
                  .append(li.getLineTotal().getCents());
            }

            bw.write(o.orderId + "|"
                + o.type.name() + "|"
                + o.tableNumber + "|"
                + o.guestCount + "|"
                + nullSafe(o.guestName) + "|"
                + o.status.name() + "|"
                + nullSafe(o.ticketId) + "|"
                + lines);
            bw.newLine();
          }
        } catch (Exception e) {
          throw new RuntimeException("Failed to save orders: " + e.getMessage());
        }
      }
    }

    static Map<String, RestaurantCore.TicketRecord> loadTickets() {
      synchronized (LOCK) {
        Map<String, RestaurantCore.TicketRecord> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(TICKETS_FILE))) {
          String line;
          while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] p = line.split("\\|", -1);
            if (p.length < 4) continue;

            String ticketId = p[0];
            String orderId = p[1];
            TicketStatus status = parseTicketStatus(p[2]);
            String lineBlob = p[3];

            List<OrderLineItem> lines = new ArrayList<>();

            if (!lineBlob.isEmpty()) {
              String[] items = lineBlob.split(",");
              for (String item : items) {
                String[] f = item.split("\\^", -1);
                if (f.length < 5) continue;

                String itemId = f[0];
                String name = f[1];
                int qty = parseIntSafe(f[2]);
                long unit = parseLongSafe(f[3]);
                long total = parseLongSafe(f[4]);

                OrderLineItem li = OrderLineItem.newBuilder()
                    .setItemId(itemId)
                    .setName(name)
                    .setQuantity(qty)
                    .setUnitPrice(Money.newBuilder().setCents(unit).setCurrency("USD").build())
                    .setLineTotal(Money.newBuilder().setCents(total).setCurrency("USD").build())
                    .build();

                lines.add(li);
              }
            }

            RestaurantCore.TicketRecord t =
                new RestaurantCore.TicketRecord(ticketId, orderId, lines);
            t.status = status;
            map.put(ticketId, t);
          }
        } catch (Exception e) {
          throw new RuntimeException("Failed to load tickets: " + e.getMessage());
        }
        return map;
      }
    }

    static void saveTickets(Map<String, RestaurantCore.TicketRecord> tickets) {
      synchronized (LOCK) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(TICKETS_FILE, false))) {
          for (RestaurantCore.TicketRecord t : tickets.values()) {
            StringBuilder lines = new StringBuilder();
            boolean first = true;
            for (OrderLineItem li : t.lines) {
              if (!first) lines.append(",");
              first = false;
              lines.append(li.getItemId()).append("^")
                  .append(li.getName()).append("^")
                  .append(li.getQuantity()).append("^")
                  .append(li.getUnitPrice().getCents()).append("^")
                  .append(li.getLineTotal().getCents());
            }

            bw.write(t.ticketId + "|"
                + t.orderId + "|"
                + t.status.name() + "|"
                + lines);
            bw.newLine();
          }
        } catch (Exception e) {
          throw new RuntimeException("Failed to save tickets: " + e.getMessage());
        }
      }
    }

    private static String nullSafe(String s) {
      return s == null ? "" : s;
    }

    private static int parseIntSafe(String s) {
      try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static long parseLongSafe(String s) {
      try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }

    private static MenuCategory parseMenuCategory(String s) {
      try { return MenuCategory.valueOf(s); } catch (Exception e) { return MenuCategory.MENU_CATEGORY_UNSPECIFIED; }
    }

    private static OrderType parseOrderType(String s) {
      try { return OrderType.valueOf(s); } catch (Exception e) { return OrderType.ORDER_TYPE_UNSPECIFIED; }
    }

    private static OrderStatus parseOrderStatus(String s) {
      try { return OrderStatus.valueOf(s); } catch (Exception e) { return OrderStatus.ORDER_STATUS_UNSPECIFIED; }
    }

    private static TicketStatus parseTicketStatus(String s) {
      try { return TicketStatus.valueOf(s); } catch (Exception e) { return TicketStatus.TICKET_STATUS_UNSPECIFIED; }
    }
  }



  // Shared core used by BOTH gRPC and TCP
  private final RestaurantCore core = new RestaurantCore();

  public HelloWorldServer(int grpcPort, int tcpPort, String serverName) {
    this.grpcPort = grpcPort;
    this.tcpPort = tcpPort;
    this.serverName = serverName;
  }

  private void start() throws IOException {
    ExecutorService executor = Executors.newFixedThreadPool(2);

    server = Grpc.newServerBuilderForPort(grpcPort, InsecureServerCredentials.create())
        .executor(executor)
        .addService(new RestaurantImpl(core))
        .build()
        .start();

    logger.info("[" + serverName + "] gRPC Server started, listening on " + grpcPort);

    // Start TCP server in a new thread
    Thread tcpThread = new Thread(() -> {
      try {
        runTcpServer(tcpPort, core, serverName);
      } catch (IOException e) {
        System.err.println("[" + serverName + "][TCP] Server failed: " + e.getMessage());
      }
    }, serverName + "-tcp-server");
    tcpThread.setDaemon(true);
    tcpThread.start();

    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        try {
          HelloWorldServer.this.stop();
        } catch (InterruptedException e) {
          if (server != null) server.shutdownNow();
          e.printStackTrace(System.err);
        } finally {
          executor.shutdown();
        }
        System.err.println("*** server shut down");
      }
    });
  }

  private void stop() throws InterruptedException {
    if (server != null) {
      server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    int grpcPort = 50051;
    int tcpPort = 50052;
    String serverName = "backend-1";

    if (args.length >= 1) {
      grpcPort = parsePort(args[0], 50051);
    }
    if (args.length >= 2) {
      tcpPort = parsePort(args[1], 50052);
    }
    if (args.length >= 3) {
      serverName = args[2];
    }

    final HelloWorldServer server = new HelloWorldServer(grpcPort, tcpPort, serverName);
    server.start();
    server.blockUntilShutdown();
  }

  private static int parsePort(String s, int fallback) {
    try {
      int p = Integer.parseInt(s.trim());
      if (p <= 0 || p > 65535) return fallback;
      return p;
    } catch (Exception e) {
      return fallback;
    }
  }

  // TCP Server

  private static void runTcpServer(int port, RestaurantCore core, String serverName) throws IOException {
    try (ServerSocket serverSocket = new ServerSocket(port)) {
      System.out.println("[" + serverName + "][TCP] Server listening on port " + port);

      while (true) {
        Socket client = serverSocket.accept();
        System.out.println("[" + serverName + "][TCP] Accepted connection from " + client.getRemoteSocketAddress());

        handleTcpClient(client, core, serverName);

        System.out.println("[" + serverName + "][TCP] Connection closed for " + client.getRemoteSocketAddress());
      }
    }
  }

  private static void handleTcpClient(Socket client, RestaurantCore core, String serverName) {
    try (
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true)
    ) {
      out.println("OK|message=CONNECTED");

      String line;
      while ((line = in.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) continue;

        if (line.startsWith("QUIT")) {
          out.println("OK|message=BYE");
          break;
        }

	System.out.println("[" + serverName + "][TCP] Received request: " + line);

        String reply = dispatchTcp(line, core, serverName);
        out.println(reply);

	System.out.println("[" +serverName + "][TCP] Sent response: " + reply);
      }
    } catch (Exception e) {
      System.err.println("[" + serverName + "][TCP] Handler error: " + e.getMessage());
    } finally {
      try { client.close(); } catch (IOException ignored) {}
    }
  }

  
  private static String dispatchTcp(String requestLine, RestaurantCore core, String serverName) {
  String[] parts = requestLine.split("\\|", 2);
  String op = parts[0].trim().toUpperCase();
  Map<String, String> kv = (parts.length > 1) ? parseKv(parts[1]) : new HashMap<>();

  try {
    System.out.println("[" + serverName + "][TCP] Handling op: " + op);

    switch (op) {
      case "LOGIN":
        return core.tcpLogin(kv.getOrDefault("username",""), kv.getOrDefault("password",""));
      case "LOGOUT":
        return core.tcpLogout(kv.getOrDefault("token",""));
      case "LIST_MENU":
        return core.tcpListMenu(kv.getOrDefault("token",""));
      case "CREATE_ORDER":
        return core.tcpCreateOrder(kv);
      case "ADD_ITEM":
        return core.tcpAddItem(kv);
      case "SUBMIT_ORDER":
        return core.tcpSubmitOrder(kv);
      case "GET_TICKETS":
        return core.tcpGetTickets(kv);
      case "ACK_TICKET":
        return core.tcpAcknowledgeTicket(kv);
      case "GET_BILL":
        return core.tcpGetBill(kv);
      case "UPDATE_MENU_ITEM":
        return core.tcpUpdateMenuItem(kv);
      default:
        return "ERROR|code=UNKNOWN_OP;message=Unknown op " + op;
    }
  } catch (Exception e) {
    System.out.println("[" + serverName + "][TCP] Exception while handling op " + op + ": " + e.getMessage());
    return "ERROR|code=EXCEPTION;message=" + safe(e.getMessage());
  }
}


  private static Map<String, String> parseKv(String s) {
    Map<String, String> map = new HashMap<>();
    String[] pairs = s.split(";");
    for (String pair : pairs) {
      pair = pair.trim();
      if (pair.isEmpty()) continue;
      int eq = pair.indexOf('=');
      if (eq < 0) continue;
      String k = pair.substring(0, eq).trim();
      String v = pair.substring(eq + 1).trim();
      map.put(k, v);
    }
    return map;
  }

  private static String safe(String msg) {
    if (msg == null) return "";
    return msg.replace("\n", " ").replace("\r", " ");
  }

  // Shared Core

  static class RestaurantCore {

    static class UserRecord {
      final String username;
      final String password;
      final UserRole role;
      UserRecord(String username, String password, UserRole role) {
        this.username = username;
        this.password = password;
        this.role = role;
      }
    }

    static class OrderRecord {
      final String orderId;
      final OrderType type;
      final int tableNumber;
      final int guestCount;
      final String guestName;
      OrderStatus status;
      final List<OrderLineItem> lines = new ArrayList<>();
      String ticketId;

      OrderRecord(String orderId, OrderType type, int tableNumber, int guestCount, String guestName) {
        this.orderId = orderId;
        this.type = type;
        this.tableNumber = tableNumber;
        this.guestCount = guestCount;
        this.guestName = guestName;
        this.status = OrderStatus.NEW;
      }
    }

    static class TicketRecord {
      final String ticketId;
      final String orderId;
      TicketStatus status;
      final List<OrderLineItem> lines;
      TicketRecord(String ticketId, String orderId, List<OrderLineItem> lines) {
        this.ticketId = ticketId;
        this.orderId = orderId;
        this.lines = lines;
        this.status = TicketStatus.TICKET_SENT;
      }
    }

    private final Map<String, UserRecord> users = new HashMap<>();
    private final Map<String, UserRole> sessions = new HashMap<>();
    private Map<String, MenuItem> menuItemsById = new HashMap<>();
    private Map<String, OrderRecord> orders = new HashMap<>();
    private Map<String, TicketRecord> tickets = new HashMap<>();

    RestaurantCore() {
      SharedDataStore.ensureFilesExist();

      users.put("manager", new UserRecord("manager", "pass", UserRole.MANAGER));
      users.put("server",  new UserRecord("server",  "pass", UserRole.SERVER));
      users.put("chef",    new UserRecord("chef",    "pass", UserRole.CHEF));

      menuItemsById = SharedDataStore.loadMenu();
      orders = SharedDataStore.loadOrders();
      tickets = SharedDataStore.loadTickets();



      if (menuItemsById.isEmpty()) 
      {
          putMenuItem("starter_fries", "Fries", MenuCategory.STARTER, 599);
          putMenuItem("starter_wings", "Wings", MenuCategory.STARTER, 1099);
          putMenuItem("starter_soup", "Soup of the Day", MenuCategory.STARTER, 799);
      
          putMenuItem("main_burger", "Burger", MenuCategory.MAIN, 1399);
          putMenuItem("main_pasta", "Pasta", MenuCategory.MAIN, 1499);
          putMenuItem("main_steak", "Steak", MenuCategory.MAIN, 2199);
          putMenuItem("main_tacos", "Tacos", MenuCategory.MAIN, 1299);
          putMenuItem("main_salmon", "Salmon", MenuCategory.MAIN, 1899);

          putMenuItem("dessert_cake", "Cake", MenuCategory.DESSERT, 699);
          putMenuItem("dessert_pie", "Pie", MenuCategory.DESSERT, 649);
          putMenuItem("dessert_icecream", "Ice Cream", MenuCategory.DESSERT, 599);

          putMenuItem("drink_coke", "Coke", MenuCategory.DRINK, 299);
          putMenuItem("drink_water", "Water", MenuCategory.DRINK, 0);
          putMenuItem("drink_tea", "Iced Tea", MenuCategory.DRINK, 249);
          putMenuItem("drink_lemonade", "Lemonade", MenuCategory.DRINK, 279);
          putMenuItem("drink_coffee", "Coffee", MenuCategory.DRINK, 249);

          SharedDataStore.saveMenu(menuItemsById);  
      }
    }

    private void putMenuItem(String id, String name, MenuCategory cat, long cents) {
      MenuItem mi = MenuItem.newBuilder()
          .setItemId(id)
          .setName(name)
          .setCategory(cat)
          .setPrice(Money.newBuilder().setCents(cents).setCurrency("USD").build())
          .setActive(true)
          .build();
      menuItemsById.put(id, mi);  
    }

    private boolean isValidSession(String token) {
      return token != null && !token.isEmpty() && sessions.containsKey(token);
    }

    private UserRole roleFor(String token) {
      return sessions.get(token);
    }

    private ErrorInfo err(String code, String msg) {
      return ErrorInfo.newBuilder().setCode(code).setMessage(msg).build();
    }

    private Money money(long cents) {
      return Money.newBuilder().setCents(cents).setCurrency("USD").build();
    }

    private long subtotalCents(OrderRecord o) {
      long sum = 0;
      for (OrderLineItem li : o.lines) sum += li.getLineTotal().getCents();
      return sum;
    }

    private long totalCentsWithTax(long subtotal) {
      long tax = (subtotal * 8 + 50) / 100;
      return subtotal + tax;
    }

    // Core operations

    LoginReply login(LoginRequest req) {
      UserRecord u = users.get(req.getUsername());
      if (u == null || !u.password.equals(req.getPassword())) {
        return LoginReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("AUTH_FAILED", "Invalid username or password"))
            .build();
      }

      String token = UUID.randomUUID().toString();
      sessions.put(token, u.role);

      return LoginReply.newBuilder()
          .setStatus(ReplyStatus.OK)
          .setSessionToken(token)
          .setRole(u.role)
          .build();
    }

    StatusReply logout(LogoutRequest req) {
      if (!isValidSession(req.getSessionToken())) {
        return StatusReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("NOT_LOGGED_IN", "Invalid or expired session token"))
            .build();
      }
      sessions.remove(req.getSessionToken());
      return StatusReply.newBuilder().setStatus(ReplyStatus.OK).build();
    }

    UpdateMenuItemReply updateMenuItem(UpdateMenuItemRequest req) {
      if (!isValidSession(req.getSessionToken())) {
        return UpdateMenuItemReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("NOT_LOGGED_IN", "Login required"))
            .build();
      }
      if (roleFor(req.getSessionToken()) != UserRole.MANAGER) {
        return UpdateMenuItemReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("FORBIDDEN", "Only MANAGER can edit the menu"))
            .build();
      }

      menuItemsById = SharedDataStore.loadMenu();

      String id = req.getItemId();
      if (id == null || id.trim().isEmpty()) {
        return UpdateMenuItemReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("INVALID", "item_id required"))
            .build();
      }

      MenuItem existing = menuItemsById.get(id);

      String name = (existing != null) ? existing.getName() : "";
      MenuCategory cat = (existing != null) ? existing.getCategory() : MenuCategory.MENU_CATEGORY_UNSPECIFIED;
      long cents = (existing != null) ? existing.getPrice().getCents() : 0;
      boolean active = (existing != null) ? existing.getActive() : true;

      if (req.getName() != null && !req.getName().trim().isEmpty()) name = req.getName().trim();
      if (req.getCategory() != MenuCategory.MENU_CATEGORY_UNSPECIFIED) cat = req.getCategory();
      if (req.getPriceCents() >= 0) cents = req.getPriceCents();
      if (req.getHasActive()) active = req.getActive();

      if (name.isEmpty()) {
        return UpdateMenuItemReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("INVALID", "name required (for new items)"))
            .build();
      }
      if (cat == MenuCategory.MENU_CATEGORY_UNSPECIFIED) {
        return UpdateMenuItemReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("INVALID", "category required (for new items)"))
            .build();
      }
      if (cents < 0) {
        return UpdateMenuItemReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("INVALID", "price_cents must be >= 0"))
            .build();
      }

      MenuItem updated = MenuItem.newBuilder()
          .setItemId(id)
          .setName(name)
          .setCategory(cat)
          .setPrice(Money.newBuilder().setCents(cents).setCurrency("USD").build())
          .setActive(active)
          .build();

      menuItemsById.put(id, updated);
      SharedDataStore.saveMenu(menuItemsById);

      return UpdateMenuItemReply.newBuilder()
          .setStatus(ReplyStatus.OK)
          .setItem(updated)
          .build();
    }

    ListMenuReply listMenu(ListMenuRequest req) {
      if (!isValidSession(req.getSessionToken())) {
        return ListMenuReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("NOT_LOGGED_IN", "Login required"))
            .build();
      }
      menuItemsById = SharedDataStore.loadMenu();
      Menu.Builder mb = Menu.newBuilder();
      for (MenuItem mi : menuItemsById.values()) {
        if (mi.getActive()) mb.addItems(mi);
      }

      return ListMenuReply.newBuilder()
          .setStatus(ReplyStatus.OK)
          .setMenu(mb.build())
          .build();
    }

    CreateOrderReply createOrder(CreateOrderRequest req) {
      if (!isValidSession(req.getSessionToken())) {
        return CreateOrderReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("NOT_LOGGED_IN", "Login required"))
            .build();
      }

      if (roleFor(req.getSessionToken()) != UserRole.SERVER) {
        return CreateOrderReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("FORBIDDEN", "Only SERVER role can create orders"))
            .build();
      }
      orders = SharedDataStore.loadOrders();

      if (req.getType() == OrderType.DINE_IN) {
        int table = req.getTableNumber();
        if (table < 1 || table > 50) {
          return CreateOrderReply.newBuilder()
              .setStatus(ReplyStatus.ERROR)
              .setError(err("INVALID", "Only table_number=1 is supported (single-table restaurant)"))
              .build();
        }
        if (req.getGuestCount() <= 0 || req.getGuestCount() > 20) {
          return CreateOrderReply.newBuilder()
              .setStatus(ReplyStatus.ERROR)
              .setError(err("INVALID", "guest_count must be 1..20"))
              .build();
        }
      } else if (req.getType() == OrderType.TAKEOUT) {
        if (req.getGuestName() == null || req.getGuestName().isEmpty()) {
          return CreateOrderReply.newBuilder()
              .setStatus(ReplyStatus.ERROR)
              .setError(err("INVALID", "Takeout requires guest_name"))
              .build();
        }
        if (hasActiveTakeoutOrder()) {
          return CreateOrderReply.newBuilder()
              .setStatus(ReplyStatus.ERROR)
              .setError(err("LIMIT", "Only one active TAKEOUT order is allowed at a time"))
              .build();
        }
      } else {
        return CreateOrderReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("INVALID", "Order type required"))
            .build();
      }

      String orderId = "order-" + UUID.randomUUID();
      OrderRecord o = new OrderRecord(
          orderId,
          req.getType(),
          req.getTableNumber(),
          req.getGuestCount(),
          req.getGuestName()
      );
      orders.put(orderId, o);

      System.out.println("[CORE] Created order " + orderId + " type=" + req.getType().name());

      SharedDataStore.saveOrders(orders);

      return CreateOrderReply.newBuilder()
          .setStatus(ReplyStatus.OK)
          .setOrderId(orderId)
          .setOrderStatus(o.status)
          .build();
    }

    AddItemReply addItem(AddItemRequest req) {
      if (!isValidSession(req.getSessionToken())) {
        return AddItemReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("NOT_LOGGED_IN", "Login required"))
            .build();
      }
      if (roleFor(req.getSessionToken()) != UserRole.SERVER) {
        return AddItemReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("FORBIDDEN", "Only SERVER role can add items"))
            .build();
      }

      orders = SharedDataStore.loadOrders();
      menuItemsById = SharedDataStore.loadMenu();

      OrderRecord o = orders.get(req.getOrderId());
      if (o == null) {
        return AddItemReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("NOT_FOUND", "Order not found"))
            .build();
      }
      if (o.status != OrderStatus.NEW) {
        return AddItemReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("INVALID_STATE", "Can only add items while order is NEW"))
            .build();
      }

      if (req.getQuantity() <= 0) {
        return AddItemReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("INVALID", "quantity must be > 0"))
            .build();
      }

      MenuItem mi = menuItemsById.get(req.getItemId());
      if (mi == null || !mi.getActive()) {
        return AddItemReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("NOT_FOUND", "Menu item not found"))
            .build();
      }

      if (o.type == OrderType.TAKEOUT) {
        int current = totalItemCount(o);
        int next = current + req.getQuantity();
        if (next > 10) {
          return AddItemReply.newBuilder()
              .setStatus(ReplyStatus.ERROR)
              .setError(err("LIMIT", "Takeout orders are limited to 10 total items"))
              .build();
        }
      }

      long unit = mi.getPrice().getCents();
      long lineTotal = unit * (long) req.getQuantity();

      OrderLineItem li = OrderLineItem.newBuilder()
          .setItemId(mi.getItemId())
          .setName(mi.getName())
          .setQuantity(req.getQuantity())
          .setUnitPrice(money(unit))
          .setLineTotal(money(lineTotal))
          .build();

      o.lines.add(li);

      SharedDataStore.saveOrders(orders);

      return AddItemReply.newBuilder()
          .setStatus(ReplyStatus.OK)
          .setOrderId(o.orderId)
          .setLineCount(o.lines.size())
          .build();
    }

    SubmitOrderReply submitOrder(SubmitOrderRequest req) {
      if (!isValidSession(req.getSessionToken())) {
        return SubmitOrderReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("NOT_LOGGED_IN", "Login required"))
            .build();
      }
      if (roleFor(req.getSessionToken()) != UserRole.SERVER) {
        return SubmitOrderReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("FORBIDDEN", "Only SERVER role can submit orders"))
            .build();
      }

      orders = SharedDataStore.loadOrders();
      tickets = SharedDataStore.loadTickets();

      OrderRecord o = orders.get(req.getOrderId());
      if (o == null) {
        return SubmitOrderReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("NOT_FOUND", "Order not found"))
            .build();
      }
      if (o.lines.isEmpty()) {
        return SubmitOrderReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("INVALID", "Cannot submit empty order"))
            .build();
      }

      String ticketId = "ticket-" + UUID.randomUUID();
      o.ticketId = ticketId;
      o.status = OrderStatus.SENT_TO_KITCHEN;

      List<OrderLineItem> copied = new ArrayList<>(o.lines);
      TicketRecord t = new TicketRecord(ticketId, o.orderId, copied);
      tickets.put(ticketId, t);

      SharedDataStore.saveOrders(orders);
      SharedDataStore.saveTickets(tickets);

      long sub = subtotalCents(o);
      long tot = totalCentsWithTax(sub);

      return SubmitOrderReply.newBuilder()
          .setStatus(ReplyStatus.OK)
          .setOrderId(o.orderId)
          .setOrderStatus(o.status)
          .setTicketId(ticketId)
          .setSubtotal(money(sub))
          .setTotal(money(tot))
          .build();
    }

    GetTicketsReply getTickets(GetTicketsRequest req) {
      if (!isValidSession(req.getSessionToken())) {
        return GetTicketsReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("NOT_LOGGED_IN", "Login required"))
            .build();
      }
      if (roleFor(req.getSessionToken()) != UserRole.CHEF) {
        return GetTicketsReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("FORBIDDEN", "Only CHEF role can get tickets"))
            .build();
      }

      tickets = SharedDataStore.loadTickets();
      TicketStatus filter = req.getStatusFilter();
      GetTicketsReply.Builder rb = GetTicketsReply.newBuilder().setStatus(ReplyStatus.OK);

      for (TicketRecord t : tickets.values()) {
        if (filter != null && filter != TicketStatus.TICKET_STATUS_UNSPECIFIED) {
          if (t.status != filter) continue;
        }
        KitchenTicket.Builder kb = KitchenTicket.newBuilder()
            .setTicketId(t.ticketId)
            .setOrderId(t.orderId)
            .setStatus(t.status);
        for (OrderLineItem li : t.lines) kb.addLines(li);
        rb.addTickets(kb.build());
      }

      return rb.build();
    }

    AcknowledgeTicketReply acknowledgeTicket(AcknowledgeTicketRequest req) {
      if (!isValidSession(req.getSessionToken())) {
        return AcknowledgeTicketReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("NOT_LOGGED_IN", "Login required"))
            .build();
      }
      if (roleFor(req.getSessionToken()) != UserRole.CHEF) {
        return AcknowledgeTicketReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("FORBIDDEN", "Only CHEF role can acknowledge tickets"))
            .build();
      }

      tickets = SharedDataStore.loadTickets();
      orders = SharedDataStore.loadOrders();

      TicketRecord t = tickets.get(req.getTicketId());
      if (t == null) {
        return AcknowledgeTicketReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("NOT_FOUND", "Ticket not found"))
            .build();
      }

      t.status = TicketStatus.TICKET_ACKNOWLEDGED;
      

      OrderRecord o = orders.get(t.orderId);
      if (o != null) o.status = OrderStatus.ACKNOWLEDGED;

      SharedDataStore.saveTickets(tickets);
      SharedDataStore.saveOrders(orders);

      return AcknowledgeTicketReply.newBuilder()
          .setStatus(ReplyStatus.OK)
          .setTicketId(t.ticketId)
          .setTicketStatus(t.status)
          .setOrderId(t.orderId)
          .setOrderStatus(o != null ? o.status : OrderStatus.ORDER_STATUS_UNSPECIFIED)
          .build();
    }

    GetBillReply getBill(GetBillRequest req) {
      if (!isValidSession(req.getSessionToken())) {
        return GetBillReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("NOT_LOGGED_IN", "Login required"))
            .build();
      }
      if (roleFor(req.getSessionToken()) != UserRole.SERVER) {
        return GetBillReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("FORBIDDEN", "Only SERVER role can request bill"))
            .build();
      }

      orders = SharedDataStore.loadOrders();

      OrderRecord o = orders.get(req.getOrderId());
      if (o == null) {
        return GetBillReply.newBuilder()
            .setStatus(ReplyStatus.ERROR)
            .setError(err("NOT_FOUND", "Order not found"))
            .build();
      }

      
      long sub = subtotalCents(o);
      long tot = totalCentsWithTax(sub);

      Bill.Builder bb = Bill.newBuilder()
          .setOrderId(o.orderId)
          .setSubtotal(money(sub))
          .setTotal(money(tot));

      for (OrderLineItem li : o.lines) bb.addLines(li);

      return GetBillReply.newBuilder()
          .setStatus(ReplyStatus.OK)
          .setBill(bb.build())
          .build();
    }

    // helper funcs

    private int totalItemCount(OrderRecord o) {
      int sum = 0;
      for (OrderLineItem li : o.lines) sum += li.getQuantity();
      return sum;
    }

    private boolean isOrderActive(OrderRecord o) {
      return o.status == OrderStatus.NEW
          || o.status == OrderStatus.SENT_TO_KITCHEN
          || o.status == OrderStatus.ACKNOWLEDGED;
    }

    private boolean hasActiveTakeoutOrder() {
      for (OrderRecord o : orders.values()) {
        if (o.type == OrderType.TAKEOUT && isOrderActive(o)) return true;
      }
      return false;
    }

    private static String moneyToString(Money m) {
      long cents = m.getCents();
      long dollars = cents / 100;
      long rem = Math.abs(cents % 100);
      return m.getCurrency() + " " + dollars + "." + (rem < 10 ? "0" + rem : "" + rem);
    }

    // TCP wrappers

    String tcpLogin(String username, String password) {
      LoginReply r = login(LoginRequest.newBuilder().setUsername(username).setPassword(password).build());
      if (r.getStatus() != ReplyStatus.OK) {
        return "ERROR|code=" + r.getError().getCode() + ";message=" + safe(r.getError().getMessage());
      }
      return "OK|token=" + r.getSessionToken() + ";role=" + r.getRole().name();
    }

    String tcpLogout(String token) {
      StatusReply r = logout(LogoutRequest.newBuilder().setSessionToken(token).build());
      if (r.getStatus() != ReplyStatus.OK) {
        return "ERROR|code=" + r.getError().getCode() + ";message=" + safe(r.getError().getMessage());
      }
      return "OK|status=OK";
    }

    String tcpUpdateMenuItem(Map<String, String> kv) {
      String token = kv.getOrDefault("token", "");
      String itemId = kv.getOrDefault("itemId", "");

      UpdateMenuItemRequest.Builder b = UpdateMenuItemRequest.newBuilder()
          .setSessionToken(token)
          .setItemId(itemId);

      String name = kv.getOrDefault("name", "").trim();
      if (!name.isEmpty()) b.setName(name);

      String catStr = kv.getOrDefault("category", "").trim().toUpperCase();
      if (!catStr.isEmpty()) {
        MenuCategory cat = MenuCategory.MENU_CATEGORY_UNSPECIFIED;
        if ("STARTER".equals(catStr)) cat = MenuCategory.STARTER;
        if ("MAIN".equals(catStr)) cat = MenuCategory.MAIN;
        if ("DESSERT".equals(catStr)) cat = MenuCategory.DESSERT;
        if ("DRINK".equals(catStr)) cat = MenuCategory.DRINK;
        b.setCategory(cat);
      }

      String priceStr = kv.getOrDefault("price", "").trim();
      if (!priceStr.isEmpty()) {
        long cents;
        try { cents = Long.parseLong(priceStr); } catch (Exception e) { cents = -2; }
        b.setPriceCents(cents);
      } else {
        b.setPriceCents(-1);
      }

      String activeStr = kv.getOrDefault("active", "").trim().toLowerCase();
      if (!activeStr.isEmpty()) {
        b.setHasActive(true);
        b.setActive("true".equals(activeStr) || "1".equals(activeStr) || "yes".equals(activeStr));
      } else {
        b.setHasActive(false);
      }

      UpdateMenuItemReply r = updateMenuItem(b.build());
      if (r.getStatus() != ReplyStatus.OK) {
        return "ERROR|code=" + r.getError().getCode() + ";message=" + safe(r.getError().getMessage());
      }

      MenuItem mi = r.getItem();
      return "OK|itemId=" + mi.getItemId()
          + ";name=" + safe(mi.getName())
          + ";category=" + mi.getCategory().name()
          + ";price=" + mi.getPrice().getCents()
          + ";active=" + mi.getActive();
    }

    String tcpListMenu(String token) {
      ListMenuReply r = listMenu(ListMenuRequest.newBuilder().setSessionToken(token).build());
      if (r.getStatus() != ReplyStatus.OK) {
        return "ERROR|code=" + r.getError().getCode() + ";message=" + safe(r.getError().getMessage());
      }

      StringBuilder sb = new StringBuilder();
      sb.append("OK|items=");

      boolean first = true;
      for (MenuItem mi : r.getMenu().getItemsList()) {
        if (!first) sb.append(",");
        first = false;
        sb.append(mi.getItemId()).append("^")
          .append(mi.getName()).append("^")
          .append(mi.getCategory().name()).append("^")
          .append(mi.getPrice().getCents());
      }
      return sb.toString();
    }

    String tcpCreateOrder(Map<String, String> kv) {
      String token = kv.getOrDefault("token", "");
      String typeStr = kv.getOrDefault("type", "DINE_IN").toUpperCase();

      CreateOrderRequest.Builder b = CreateOrderRequest.newBuilder().setSessionToken(token);

      if ("DINE_IN".equals(typeStr)) {
        b.setType(OrderType.DINE_IN);
        int table = parseIntSafe(kv.getOrDefault("table", "0"));
        int guests = parseIntSafe(kv.getOrDefault("guests", "0"));
        b.setTableNumber(table).setGuestCount(guests);
      } else if ("TAKEOUT".equals(typeStr)) {
        b.setType(OrderType.TAKEOUT);
        b.setGuestName(kv.getOrDefault("guest", ""));
      } else {
        b.setType(OrderType.ORDER_TYPE_UNSPECIFIED);
      }

      CreateOrderReply r = createOrder(b.build());
      if (r.getStatus() != ReplyStatus.OK) {
        return "ERROR|code=" + r.getError().getCode() + ";message=" + safe(r.getError().getMessage());
      }
      return "OK|orderId=" + r.getOrderId() + ";orderStatus=" + r.getOrderStatus().name();
    }

    String tcpAddItem(Map<String, String> kv) {
      String token = kv.getOrDefault("token", "");
      String orderId = kv.getOrDefault("orderId", "");
      String itemId = kv.getOrDefault("itemId", "");
      int qty = parseIntSafe(kv.getOrDefault("qty", "0"));

      AddItemReply r = addItem(AddItemRequest.newBuilder()
          .setSessionToken(token)
          .setOrderId(orderId)
          .setItemId(itemId)
          .setQuantity(qty)
          .build());

      if (r.getStatus() != ReplyStatus.OK) {
        return "ERROR|code=" + r.getError().getCode() + ";message=" + safe(r.getError().getMessage());
      }
      return "OK|orderId=" + r.getOrderId() + ";lineCount=" + r.getLineCount();
    }

    String tcpSubmitOrder(Map<String, String> kv) {
      String token = kv.getOrDefault("token", "");
      String orderId = kv.getOrDefault("orderId", "");

      SubmitOrderReply r = submitOrder(SubmitOrderRequest.newBuilder()
          .setSessionToken(token)
          .setOrderId(orderId)
          .build());

      if (r.getStatus() != ReplyStatus.OK) {
        return "ERROR|code=" + r.getError().getCode() + ";message=" + safe(r.getError().getMessage());
      }
      return "OK|orderId=" + r.getOrderId()
          + ";orderStatus=" + r.getOrderStatus().name()
          + ";ticketId=" + r.getTicketId()
          + ";subtotal=" + safe(moneyToString(r.getSubtotal()))
          + ";total=" + safe(moneyToString(r.getTotal()));
    }

    String tcpGetTickets(Map<String, String> kv) {
      String token = kv.getOrDefault("token", "");
      String statusStr = kv.getOrDefault("status", "TICKET_SENT").toUpperCase();

      TicketStatus filter = TicketStatus.TICKET_STATUS_UNSPECIFIED;
      if ("TICKET_SENT".equals(statusStr)) filter = TicketStatus.TICKET_SENT;
      if ("TICKET_ACKNOWLEDGED".equals(statusStr)) filter = TicketStatus.TICKET_ACKNOWLEDGED;

      GetTicketsReply r = getTickets(GetTicketsRequest.newBuilder()
          .setSessionToken(token)
          .setStatusFilter(filter)
          .build());

      if (r.getStatus() != ReplyStatus.OK) {
        return "ERROR|code=" + r.getError().getCode() + ";message=" + safe(r.getError().getMessage());
      }

      StringBuilder sb = new StringBuilder();
      sb.append("OK|count=").append(r.getTicketsCount()).append(";tickets=");

      boolean first = true;
      for (KitchenTicket t : r.getTicketsList()) {
        if (!first) sb.append(",");
        first = false;
        sb.append(t.getTicketId()).append("^")
          .append(t.getOrderId()).append("^")
          .append(t.getStatus().name());
      }
      return sb.toString();
    }

    String tcpAcknowledgeTicket(Map<String, String> kv) {
      String token = kv.getOrDefault("token", "");
      String ticketId = kv.getOrDefault("ticketId", "");

      AcknowledgeTicketReply r = acknowledgeTicket(AcknowledgeTicketRequest.newBuilder()
          .setSessionToken(token)
          .setTicketId(ticketId)
          .build());

      if (r.getStatus() != ReplyStatus.OK) {
        return "ERROR|code=" + r.getError().getCode() + ";message=" + safe(r.getError().getMessage());
      }

      return "OK|ticketId=" + r.getTicketId()
          + ";ticketStatus=" + r.getTicketStatus().name()
          + ";orderId=" + r.getOrderId()
          + ";orderStatus=" + r.getOrderStatus().name();
    }

    String tcpGetBill(Map<String, String> kv) {
      String token = kv.getOrDefault("token", "");
      String orderId = kv.getOrDefault("orderId", "");

      GetBillReply r = getBill(GetBillRequest.newBuilder()
          .setSessionToken(token)
          .setOrderId(orderId)
          .build());

      if (r.getStatus() != ReplyStatus.OK) {
        return "ERROR|code=" + r.getError().getCode() + ";message=" + safe(r.getError().getMessage());
      }

      Bill b = r.getBill();
      StringBuilder sb = new StringBuilder();
      sb.append("OK|orderId=").append(b.getOrderId())
        .append(";subtotal=").append(safe(moneyToString(b.getSubtotal())))
        .append(";total=").append(safe(moneyToString(b.getTotal())))
        .append(";lines=");

      boolean first = true;
      for (OrderLineItem li : b.getLinesList()) {
        if (!first) sb.append(",");
        first = false;
        sb.append(li.getItemId()).append("^")
          .append(li.getName()).append("^")
          .append(li.getQuantity()).append("^")
          .append(li.getLineTotal().getCents());
      }
      return sb.toString();
    }

    private static int parseIntSafe(String s) {
      try { return Integer.parseInt(s.trim()); } catch (Exception ignored) { return 0; }
    }
  }

  // gRPC service wrapper

  static class RestaurantImpl extends RestaurantGrpc.RestaurantImplBase {
    private final RestaurantCore core;

    RestaurantImpl(RestaurantCore core) { this.core = core; }

    @Override public void login(LoginRequest req, StreamObserver<LoginReply> obs) { obs.onNext(core.login(req)); obs.onCompleted(); }
    @Override public void logout(LogoutRequest req, StreamObserver<StatusReply> obs) { obs.onNext(core.logout(req)); obs.onCompleted(); }
    @Override public void listMenu(ListMenuRequest req, StreamObserver<ListMenuReply> obs) { obs.onNext(core.listMenu(req)); obs.onCompleted(); }
    @Override public void createOrder(CreateOrderRequest req, StreamObserver<CreateOrderReply> obs) { obs.onNext(core.createOrder(req)); obs.onCompleted(); }
    @Override public void addItem(AddItemRequest req, StreamObserver<AddItemReply> obs) { obs.onNext(core.addItem(req)); obs.onCompleted(); }
    @Override public void submitOrder(SubmitOrderRequest req, StreamObserver<SubmitOrderReply> obs) { obs.onNext(core.submitOrder(req)); obs.onCompleted(); }
    @Override public void getTickets(GetTicketsRequest req, StreamObserver<GetTicketsReply> obs) { obs.onNext(core.getTickets(req)); obs.onCompleted(); }
    @Override public void acknowledgeTicket(AcknowledgeTicketRequest req, StreamObserver<AcknowledgeTicketReply> obs) { obs.onNext(core.acknowledgeTicket(req)); obs.onCompleted(); }
    @Override public void getBill(GetBillRequest req, StreamObserver<GetBillReply> obs) { obs.onNext(core.getBill(req)); obs.onCompleted(); }
    @Override public void updateMenuItem(UpdateMenuItemRequest req, StreamObserver<UpdateMenuItemReply> obs) { obs.onNext(core.updateMenuItem(req)); obs.onCompleted(); }
  }
}
