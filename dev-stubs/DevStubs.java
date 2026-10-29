import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DEV ONLY. Throwaway stand-ins for the Account, Ledger, Limits and Notification services, so the
 * Transfer service can be run and demoed before the real ones exist. Not part of the submission.
 *
 * Run (JDK 21, no build needed):   java dev-stubs/DevStubs.java
 *
 * Ports match the Transfer service defaults: Account 8081, Ledger 8083, Limits 8084, Notification 8085.
 *
 * Seed accounts: ACC-1001 (10,000 INR), ACC-2002 (5,000 INR), ACC-EMPTY (0 INR), ACC-FROZEN (inactive).
 * Limits: 200,000 per account per day.
 *
 * Fault switches (change while running):
 *   curl -X POST "localhost:8083/stub/fail?on=true"     Ledger answers 503 (forces a step-3 failure)
 *   curl -X POST "localhost:8081/stub/fail?on=true"     Account answers 503
 *   curl -X POST "localhost:8085/stub/fail?on=true"     Notification answers 503
 *   curl -X POST "localhost:8081/stub/state"            show balances and holds
 */
public class DevStubs {

    static final Map<String, BigDecimal> available = new HashMap<>();
    static final Map<String, BigDecimal> balance = new HashMap<>();
    static final Map<String, Boolean> active = new HashMap<>();
    static final Map<String, String[]> holds = new HashMap<>();          // transferId -> {src, dst, amount, state}
    static final Map<String, BigDecimal> limitUsed = new HashMap<>();
    static final Map<String, String[]> reservations = new HashMap<>();   // transferId -> {account, amount}
    static final Map<String, String> journals = new HashMap<>();         // transferId -> POSTED / REVERSED
    static final Map<Integer, Boolean> failing = new HashMap<>();
    static final BigDecimal DAILY_LIMIT = new BigDecimal("200000");

    public static void main(String[] args) throws IOException {
        seed("ACC-1001", "10000", true);
        seed("ACC-2002", "5000", true);
        seed("ACC-EMPTY", "0", true);
        seed("ACC-FROZEN", "1000", false);

        // Skip stubs whose real service is already running, e.g.: java dev-stubs/DevStubs.java --skip account,ledger
        java.util.Set<String> skip = new java.util.HashSet<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--skip")) skip.addAll(java.util.Arrays.asList(args[i + 1].toLowerCase().split(",")));
        }
        StringBuilder running = new StringBuilder();
        if (!skip.contains("account")) { start(8081, "account", DevStubs::account); running.append(" account 8081"); }
        if (!skip.contains("ledger")) { start(8083, "ledger", DevStubs::ledger); running.append(" ledger 8083"); }
        if (!skip.contains("limits")) { start(8084, "limits", DevStubs::limits); running.append(" limits 8084"); }
        if (!skip.contains("notification")) { start(8085, "notification", DevStubs::notification); running.append(" notification 8085"); }
        System.out.println("Dev stubs running:" + running + (skip.isEmpty() ? "" : "   (skipped: " + skip + ")"));
    }

    interface Handler { void handle(HttpExchange ex, String path, String body) throws Exception; }

    static void start(int port, String name, Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (path.equals("/health")) { send(ex, 200, "{\"status\":\"UP\",\"service\":\"" + name + "-stub\"}"); return; }
                if (path.equals("/stub/fail")) {
                    boolean on = String.valueOf(ex.getRequestURI().getQuery()).contains("on=true");
                    synchronized (DevStubs.class) { failing.put(port, on); }
                    send(ex, 200, "{\"" + name + "Failing\":" + on + "}");
                    return;
                }
                if (path.equals("/stub/state")) { synchronized (DevStubs.class) { send(ex, 200, state()); } return; }
                boolean fail;
                synchronized (DevStubs.class) { fail = failing.getOrDefault(port, false); }
                if (fail) { error(ex, 503, "STUB_FORCED_FAILURE", name + " stub is switched to fail"); return; }
                if (name.equals("notification")) {
                    handler.handle(ex, path, body); // may sleep; must not block the other stubs
                } else {
                    synchronized (DevStubs.class) { handler.handle(ex, path, body); }
                }
                System.out.printf("%s %s %s -> %d  X-Request-ID=%s Idempotency-Key=%s%n", name, ex.getRequestMethod(), path,
                        ex.getResponseCode(), ex.getRequestHeaders().getFirst("X-Request-ID"),
                        ex.getRequestHeaders().getFirst("Idempotency-Key"));
            } catch (Exception e) {
                error(ex, 500, "STUB_ERROR", e.toString());
            }
        });
        server.start();
    }

    // ---- Account: holds keyed by transferId -------------------------------------------------------
    static void account(HttpExchange ex, String path, String body) throws IOException {
        Matcher m;
        if (path.equals("/api/v1/holds")) {
            String id = field(body, "transferId"), src = field(body, "sourceAccountId"),
                    dst = field(body, "destinationAccountId");
            BigDecimal amount = new BigDecimal(field(body, "amount"));
            if (holds.containsKey(id)) { send(ex, 200, "{\"transferId\":\"" + id + "\",\"state\":\"" + holds.get(id)[3] + "\"}"); return; }
            if (!available.containsKey(src) || !available.containsKey(dst)) { error(ex, 404, "ACCOUNT_NOT_FOUND", "unknown account"); return; }
            if (!active.get(src) || !active.get(dst)) { error(ex, 422, "ACCOUNT_NOT_ACTIVE", "account is not active"); return; }
            if (available.get(src).compareTo(amount) < 0) { error(ex, 409, "INSUFFICIENT_FUNDS", "available balance is too low"); return; }
            available.merge(src, amount.negate(), BigDecimal::add);
            holds.put(id, new String[]{src, dst, amount.toPlainString(), "HELD"});
            send(ex, 201, "{\"transferId\":\"" + id + "\",\"state\":\"HELD\"}");
        } else if ((m = Pattern.compile("/api/v1/holds/([^/]+)/(capture|release)").matcher(path)).matches()) {
            String[] h = holds.get(m.group(1));
            if (h == null) { error(ex, 404, "HOLD_NOT_FOUND", "no hold"); return; }
            BigDecimal amount = new BigDecimal(h[2]);
            if (m.group(2).equals("capture")) {
                if (h[3].equals("CAPTURED")) { send(ex, 200, "{\"state\":\"CAPTURED\"}"); return; }
                if (!h[3].equals("HELD")) { error(ex, 409, "HOLD_NOT_ACTIVE", "hold is " + h[3]); return; }
                balance.merge(h[0], amount.negate(), BigDecimal::add);
                balance.merge(h[1], amount, BigDecimal::add);
                available.merge(h[1], amount, BigDecimal::add);
                h[3] = "CAPTURED";
            } else {
                if (h[3].equals("RELEASED")) { send(ex, 200, "{\"state\":\"RELEASED\"}"); return; }
                if (h[3].equals("CAPTURED")) { error(ex, 409, "HOLD_ALREADY_CAPTURED", "hold was captured"); return; }
                available.merge(h[0], amount, BigDecimal::add);
                h[3] = "RELEASED";
            }
            send(ex, 200, "{\"state\":\"" + h[3] + "\"}");
        } else {
            error(ex, 404, "NOT_FOUND", path);
        }
    }

    // ---- Ledger -------------------------------------------------------------------------------------
    static void ledger(HttpExchange ex, String path, String body) throws IOException {
        Matcher m;
        if (path.equals("/api/v1/journals")) {
            String id = field(body, "transferId");
            int status = journals.containsKey(id) ? 200 : 201;
            journals.putIfAbsent(id, "POSTED");
            send(ex, status, "{\"transferId\":\"" + id + "\",\"state\":\"" + journals.get(id) + "\"}");
        } else if ((m = Pattern.compile("/api/v1/journals/([^/]+)/reversal").matcher(path)).matches()) {
            if (!journals.containsKey(m.group(1))) { error(ex, 404, "JOURNAL_NOT_FOUND", "no journal"); return; }
            journals.put(m.group(1), "REVERSED");
            send(ex, 201, "{\"state\":\"REVERSED\"}");
        } else {
            error(ex, 404, "NOT_FOUND", path);
        }
    }

    // ---- Limits -------------------------------------------------------------------------------------
    static void limits(HttpExchange ex, String path, String body) throws IOException {
        Matcher m;
        if (path.equals("/api/v1/limit-reservations") && ex.getRequestMethod().equals("POST")) {
            String id = field(body, "transferId"), account = field(body, "accountId");
            BigDecimal amount = new BigDecimal(field(body, "amount"));
            if (reservations.containsKey(id)) { send(ex, 200, "{\"transferId\":\"" + id + "\"}"); return; }
            BigDecimal used = limitUsed.getOrDefault(account, BigDecimal.ZERO);
            if (used.add(amount).compareTo(DAILY_LIMIT) > 0) {
                error(ex, 422, "LIMIT_EXCEEDED", "daily limit of " + DAILY_LIMIT + " would be exceeded");
                return;
            }
            limitUsed.put(account, used.add(amount));
            reservations.put(id, new String[]{account, amount.toPlainString()});
            send(ex, 201, "{\"transferId\":\"" + id + "\"}");
        } else if ((m = Pattern.compile("/api/v1/limit-reservations/([^/]+)").matcher(path)).matches()
                && ex.getRequestMethod().equals("DELETE")) {
            String[] r = reservations.remove(m.group(1));
            if (r == null) { error(ex, 404, "RESERVATION_NOT_FOUND", "nothing to release"); return; }
            limitUsed.merge(r[0], new BigDecimal(r[1]).negate(), BigDecimal::add);
            ex.sendResponseHeaders(204, -1);
            ex.close();
        } else {
            error(ex, 404, "NOT_FOUND", path);
        }
    }

    // ---- Notification -------------------------------------------------------------------------------
    static void notification(HttpExchange ex, String path, String body) throws Exception {
        long delay = Long.parseLong(System.getenv().getOrDefault("NOTIFY_DELAY_MS", "0"));
        double failRate = Double.parseDouble(System.getenv().getOrDefault("NOTIFY_FAIL_RATE", "0"));
        if (delay > 0) Thread.sleep(delay);
        if (ThreadLocalRandom.current().nextDouble() < failRate) { error(ex, 503, "PROVIDER_DOWN", "simulated SMS failure"); return; }
        send(ex, 202, "{\"accepted\":true}");
    }

    // ---- helpers ------------------------------------------------------------------------------------
    static void seed(String id, String amount, boolean isActive) {
        available.put(id, new BigDecimal(amount));
        balance.put(id, new BigDecimal(amount));
        active.put(id, isActive);
    }

    /** Minimal JSON field reader, good enough for the flat bodies the Transfer service sends. */
    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]*)\"?").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("missing field " + name);
        return m.group(1).trim();
    }

    static String state() {
        StringBuilder sb = new StringBuilder("{\"accounts\":{");
        available.keySet().stream().sorted().forEach(a -> sb.append("\"").append(a).append("\":{\"balance\":")
                .append(balance.get(a).toPlainString()).append(",\"available\":").append(available.get(a).toPlainString())
                .append("},"));
        sb.setLength(sb.length() - 1);
        sb.append("},\"holds\":{");
        holds.forEach((k, v) -> sb.append("\"").append(k).append("\":\"").append(v[3]).append("\","));
        if (!holds.isEmpty()) sb.setLength(sb.length() - 1);
        sb.append("},\"journals\":{");
        journals.forEach((k, v) -> sb.append("\"").append(k).append("\":\"").append(v).append("\","));
        if (!journals.isEmpty()) sb.setLength(sb.length() - 1);
        return sb.append("}}").toString();
    }

    static void error(HttpExchange ex, int status, String code, String message) throws IOException {
        send(ex, status, "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message.replace("\"", "'")
                + "\",\"requestId\":null,\"details\":[]}}");
    }

    static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
