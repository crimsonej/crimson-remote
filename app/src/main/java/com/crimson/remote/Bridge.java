package com.crimson.remote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The phone-side bridge: serves the Crimson web app to other devices on the
 * Wi-Fi (an iPhone, a laptop) and relays their button presses to the TV.
 * Same HTTP API as bridge/crimson_bridge.py.
 */
public class Bridge implements Mqtt.Listener {
    public static final int HTTP_PORT = 8787;
    static final int TV_PORT = 36669;
    static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context ctx;
    private final SharedPreferences prefs;
    private final String cid;
    private volatile Mqtt mqtt;
    private volatile boolean running;
    private ServerSocket server;
    private final List<OutputStream> streams = Collections.synchronizedList(new ArrayList<OutputStream>());
    private final JSONObject snapshot = new JSONObject();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public Bridge(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("crimson", Context.MODE_PRIVATE);
        String id = prefs.getString("bridge_cid", null);
        if (id == null) {
            id = "CrimsonWeb-" + UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString("bridge_cid", id).apply();
        }
        cid = id;
        put("status", "connecting");
        TvStore.Tv cur = new TvStore(ctx).current();
        put("model", cur != null ? cur.name : null);
        put("pairing", false);
    }

    public boolean isRunning() { return running; }

    public void start() throws IOException {
        if (running) return;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(HTTP_PORT));
        running = true;
        pool.execute(this::acceptLoop);
        pool.execute(this::keepConnected);
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        Mqtt m = mqtt; mqtt = null;
        if (m != null) m.close();
        synchronized (streams) {
            for (OutputStream o : streams) try { o.close(); } catch (IOException ignored) {}
            streams.clear();
        }
        pool.shutdownNow();
    }

    /** http://<phone>:8787 on the current Wi-Fi, or null. */
    public static String address() {
        String ip = localIp();
        return ip == null ? null : "http://" + ip + ":" + HTTP_PORT;
    }

    // ------------------------------------------------------------ TV side

    private void keepConnected() {
        while (running) {
            Mqtt m = mqtt;
            if (m == null || !m.isConnected()) {
                TvStore.Tv cur = new TvStore(ctx).current();
                String host = cur != null && TvDriver.HISENSE.equals(cur.brand) ? cur.ip : "";
                boolean ok = !host.isEmpty() && tryConnect(host);
                if (!ok) {
                    String found = scan();
                    if (found != null) {
                        ok = tryConnect(found);
                    }
                }
                if (!ok) emit("status", "offline");
            }
            sleep(ok() ? 3000 : 7000);
        }
    }

    private boolean ok() { Mqtt m = mqtt; return m != null && m.isConnected(); }

    private boolean tryConnect(String host) {
        Mqtt m = new Mqtt(host, TV_PORT, cid, "hisenseservice", "multimqttservice", this);
        try {
            m.connect();
            mqtt = m;
            m.subscribe("/remoteapp/mobile/#");
            refresh();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public void onConnected() { emit("status", "connected"); }

    @Override public void onDisconnected(String why) { mqtt = null; emit("status", "offline"); }

    @Override public void onMessage(String topic, String payload) {
        try {
            if (topic.endsWith("/data/authentication")) {
                emit("pairing", true);
                JSONObject o = new JSONObject();
                o.put("app_version", 2); o.put("connect_result", 0); o.put("device_type", "Mobile App");
                pub("ui_service", "vidaa_app_connect", o.toString());
            } else if (topic.endsWith("/data/authenticationcode")) {
                boolean good = new JSONObject(payload).optInt("result") == 1;
                emit("pairing", good ? (Object) false : "retry");
                if (good) refresh();
            } else if (topic.endsWith("/ui_service/state") || topic.endsWith("/ui_service/data/state")) {
                emit("state", new JSONObject(payload));
            } else if (topic.endsWith("/data/capability")) {
                JSONObject o = new JSONObject(payload);
                String model = o.optString("devicemsg", "").split("_")[0];
                if (!model.isEmpty()) {
                    String name = ("his".equals(o.optString("vendorBrand")) ? "Hisense " : "") + model;
                    prefs.edit().putString("name", name).apply();
                    emit("model", name);
                }
            } else if (topic.endsWith("/data/sourcelist")) {
                emit("sources", new JSONArray(payload));
            } else if (topic.endsWith("/data/applist")) {
                emit("apps", new JSONArray(payload));
            } else if (topic.contains("volumechange") || topic.endsWith("/data/volume") || topic.endsWith("/ui_service/volume")) {
                int v = new JSONObject(payload).optInt("volume_value", -1);
                if (v >= 0) emit("volume", v);
            } else if (topic.endsWith("/actions/tvsleep")) {
                JSONObject s = new JSONObject();
                s.put("statetype", "fake_sleep_0");
                emit("state", s);
            }
        } catch (Exception ignored) {}
    }

    private void pub(String service, String action, String payload) throws IOException {
        Mqtt m = mqtt;
        if (m == null || !m.isConnected()) throw new IOException("Not connected to the TV");
        m.publish("/remoteapp/tv/" + service + "/" + cid + "/actions/" + action, payload);
    }

    private void refresh() {
        try {
            pub("ui_service", "gettvstate", "");
            pub("ui_service", "capability", "");
            pub("ui_service", "sourcelist", "");
            pub("ui_service", "applist", "");
            pub("platform_service", "getvolume", "");
        } catch (IOException ignored) {}
    }

    private void wake(String requested) throws IOException {
        TvStore.Tv cur = new TvStore(ctx).current();
        String mac = TvStore.normalMac(requested);
        if (mac.isEmpty() && cur != null) mac = cur.mac;
        if (mac == null || mac.isEmpty()) throw new IOException("This TV's MAC address isn't known yet");
        String[] h = mac.split("[:-]");
        byte[] m = new byte[6];
        for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(h[i], 16);
        byte[] p = new byte[102];
        for (int i = 0; i < 6; i++) p[i] = (byte) 0xFF;
        for (int i = 6; i < 102; i++) p[i] = m[(i - 6) % 6];
        DatagramSocket s = new DatagramSocket();
        s.setBroadcast(true);
        String ip = localIp();
        for (int i = 0; i < 3; i++) {
            if (ip != null) s.send(new DatagramPacket(p, p.length, InetAddress.getByName(ip.substring(0, ip.lastIndexOf('.')) + ".255"), 9));
            s.send(new DatagramPacket(p, p.length, InetAddress.getByName("255.255.255.255"), 9));
        }
        s.close();
    }

    private String scan() {
        TvStore.Tv cur = new TvStore(ctx).current();
        String base = cur != null ? cur.ip : "";
        String prefix = !base.isEmpty() ? base.substring(0, base.lastIndexOf('.') + 1) : null;
        if (prefix == null) {
            String ip = localIp();
            if (ip == null) return null;
            prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
        }
        final String pre = prefix;
        final AtomicReference<String> hit = new AtomicReference<>();
        ExecutorService p = Executors.newFixedThreadPool(40);
        for (int i = 1; i < 255; i++) {
            final String host = pre + i;
            p.execute(() -> {
                if (hit.get() != null) return;
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, TV_PORT), 400);
                    hit.compareAndSet(null, host);
                } catch (Exception ignored) {}
            });
        }
        p.shutdown();
        try { p.awaitTermination(9, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return hit.get();
    }

    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                if (!n.startsWith("wlan") && !n.startsWith("eth") && !n.startsWith("ap")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address) return a.getHostAddress();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ------------------------------------------------------------ events

    private void put(String k, Object v) {
        synchronized (snapshot) { try { snapshot.put(k, v == null ? JSONObject.NULL : v); } catch (Exception ignored) {} }
    }

    private void emit(String type, Object data) {
        put(type, data);
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("data", data);
            broadcast(o.toString());
        } catch (Exception ignored) {}
    }

    private void broadcast(String msg) {
        byte[] b = ("data: " + msg + "\n\n").getBytes(UTF8);
        synchronized (streams) {
            for (int i = streams.size() - 1; i >= 0; i--) {
                try { streams.get(i).write(b); streams.get(i).flush(); }
                catch (IOException e) { streams.remove(i); }
            }
        }
    }

    // ------------------------------------------------------------ HTTP side

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket s) {
        boolean keep = false;
        try {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], target = parts[1];
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("content-length"))
                    contentLength = Integer.parseInt(line.substring(c + 1).trim());
            }
            byte[] body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int r = in.read(body, read, contentLength - read);
                if (r < 0) break;
                read += r;
            }
            String path = target.split("\\?")[0];
            OutputStream out = s.getOutputStream();

            if (method.equals("OPTIONS")) { respond(out, 204, "text/plain", new byte[0]); return; }
            if (method.equals("GET") && path.equals("/api/events")) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n"
                        + "Access-Control-Allow-Origin: *\r\nConnection: keep-alive\r\n\r\n").getBytes(UTF8));
                String snap;
                synchronized (snapshot) { snap = new JSONObject().put("type", "snapshot").put("data", snapshot).toString(); }
                out.write(("data: " + snap + "\n\n").getBytes(UTF8));
                out.flush();
                s.setSoTimeout(0);
                streams.add(out);
                keep = true;
                pool.execute(() -> heartbeat(out, s));
                return;
            }
            if (method.equals("GET") && path.equals("/api/snapshot")) {
                String snap;
                synchronized (snapshot) { snap = snapshot.toString(); }
                respond(out, 200, "application/json", snap.getBytes(UTF8));
                return;
            }
            if (method.equals("POST") && path.startsWith("/api/")) {
                JSONObject b = new JSONObject(contentLength == 0 ? "{}" : new String(body, UTF8));
                try {
                    route(path, b);
                    respond(out, 200, "application/json", "{\"ok\":true}".getBytes(UTF8));
                } catch (Exception e) {
                    respond(out, 409, "application/json",
                            new JSONObject().put("error", String.valueOf(e.getMessage())).toString().getBytes(UTF8));
                }
                return;
            }
            if (method.equals("GET")) { serveAsset(out, path); return; }
            respond(out, 405, "text/plain", "Method not allowed".getBytes(UTF8));
        } catch (Exception ignored) {
        } finally {
            if (!keep) try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void heartbeat(OutputStream out, Socket s) {
        while (running && streams.contains(out)) {
            sleep(15000);
            try {
                synchronized (streams) { out.write(": ping\n\n".getBytes(UTF8)); out.flush(); }
            } catch (IOException e) {
                streams.remove(out);
                try { s.close(); } catch (IOException ignored) {}
                return;
            }
        }
    }

    private void route(String path, JSONObject b) throws Exception {
        switch (path) {
            case "/api/key": pub("remote_service", "sendkey", b.getString("key")); break;
            case "/api/text": pub("remote_service", "input", b.getString("code")); break;
            case "/api/volume": pub("platform_service", "changevolume", String.valueOf(b.getInt("value"))); break;
            case "/api/source":
                pub("ui_service", "changesource", new JSONObject()
                        .put("sourceid", b.optString("sourceid")).put("sourcename", b.optString("sourcename")).toString());
                break;
            case "/api/app":
                pub("ui_service", "launchapp", new JSONObject()
                        .put("appId", b.optString("appId")).put("name", b.optString("name"))
                        .put("url", b.optString("url")).put("urlType", b.optInt("urlType", 37))
                        .put("storeType", b.optInt("storeType", 0)).toString());
                break;
            case "/api/pair": pub("ui_service", "authenticationcode", new JSONObject().put("authNum", b.getString("code")).toString()); break;
            case "/api/wake": wake(b.optString("mac", "")); break;
            case "/api/refresh": refresh(); break;
            case "/api/settings": {
                Mqtt m = mqtt; mqtt = null;
                if (m != null) m.close();
                break;
            }
            default: throw new IOException("Unknown endpoint");
        }
    }

    private void serveAsset(OutputStream out, String path) throws IOException {
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..")) { respond(out, 404, "text/plain", new byte[0]); return; }
        try (InputStream a = ctx.getAssets().open("web" + path)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = a.read(chunk)) > 0) buf.write(chunk, 0, n);
            respond(out, 200, mime(path), buf.toByteArray());
        } catch (IOException e) {
            respond(out, 404, "text/plain", "Not found".getBytes(UTF8));
        }
    }

    private static String mime(String p) {
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "text/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".webmanifest")) return "application/manifest+json";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static void respond(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 204 ? "No Content" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length
                + "\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Content-Type, Authorization"
                + "\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(UTF8));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
