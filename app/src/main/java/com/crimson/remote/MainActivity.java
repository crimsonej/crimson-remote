package com.crimson.remote;

import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.crimson.remote.Ui.*;

public class MainActivity extends Activity implements Mqtt.Listener {

    static final int PORT = 36669;

    final ExecutorService net = Executors.newSingleThreadExecutor();
    SharedPreferences prefs;
    Mqtt mqtt;
    String cid;
    boolean resumed, pairingShown, playing = true, pointerMode;

    // Header + now-playing card
    TextView title, statusText, nowTitle, nowSub, volValue;
    View statusDot;
    ValueAnimator pulse;
    SeekBar volBar;
    boolean userDraggingVol;
    long lastVolSent;

    // Tabs
    FrameLayout pages;
    View[] pageViews = new View[4];
    TextView[] tabLabels = new TextView[4];
    View tabIndicator;
    int currentTab;

    // TVs found on the Wi-Fi: {ip, name, model}
    final List<String[]> devices = new ArrayList<>();
    volatile String connectedIp;
    volatile boolean scanning;
    TextView deviceBadge;

    LinearLayout appsRow;
    EditText typeBox;
    String typed = "";
    JSONArray sources = new JSONArray();

    // ================================================================ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("crimson", MODE_PRIVATE);
        cid = prefs.getString("cid", null);
        if (cid == null) {
            cid = "Crimson-" + UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString("cid", cid).apply();
        }
        applyExtras(getIntent());
        Ui.applyTheme(this);
        Window w = getWindow();
        w.setStatusBarColor(BG_TOP);
        w.setNavigationBarColor(BG_BOTTOM);
        int flags = 0;
        if (!dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
        setContentView(build());
        renderApps(null);
        showTab(0, false);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        applyExtras(i);
    }

    /** Optional: `am start -e ip 192.168.1.x -e mac AA:BB:.. [-e cid <paired id>]` pre-fills the TV. */
    void applyExtras(Intent i) {
        if (i == null) return;
        SharedPreferences.Editor e = prefs.edit();
        if (i.getStringExtra("ip") != null) e.putString("ip", i.getStringExtra("ip"));
        if (i.getStringExtra("mac") != null) e.putString("mac", i.getStringExtra("mac"));
        if (i.getStringExtra("cid") != null) { cid = i.getStringExtra("cid"); e.putString("cid", cid); }
        String bridgeExtra = i.getStringExtra("bridge");
        e.apply();
        if ("on".equals(bridgeExtra)) { prefs.edit().putBoolean("bridge_on", true).apply(); BridgeService.start(this); }
        if ("off".equals(bridgeExtra)) { prefs.edit().putBoolean("bridge_on", false).apply(); BridgeService.stop(this); }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        connect();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        final Mqtt m = mqtt;
        mqtt = null;
        if (m != null) net.execute(m::close);
        final TvDriver d = driver;
        driver = null;
        if (d != null) net.execute(d::close);
    }

    // ================================================================ connection

    /** The non-Hisense TV currently controlled (Roku, Samsung, LG), or null. */
    volatile TvDriver driver;

    String brandOf(String host) {
        synchronized (devices) { for (String[] d : devices) if (d[0].equals(host)) return d[3]; }
        return prefs.getString("brand_" + host, TvDriver.HISENSE);
    }

    boolean connectTo(String host) {
        String brand = brandOf(host);
        return TvDriver.HISENSE.equals(brand) ? tryConnect(host) : tryDriver(host, brand);
    }

    boolean tryDriver(String host, String brand) {
        if (!resumed) return false;
        TvDriver d = TvDriver.create(this, brand, host, new TvDriver.Listener() {
            @Override public void onConnected() { MAIN.post(() -> { setStatus(GREEN, "Connected · " + host, false); refreshHeader(); }); }
            @Override public void onDisconnected() {
                MAIN.post(() -> { if (driver != null && driver.ip.equals(host)) { driver = null; connectedIp = null; setStatus(RED, "Disconnected · tap to reconnect", false); } });
            }
            @Override public void onPrompt(String m) { MAIN.post(() -> { setStatus(AMBER, "Waiting for the TV", true); swap(nowTitle, "Check your TV"); swap(nowSub, m); toast(m); }); }
            @Override public void onState(String t, String sub) { MAIN.post(() -> { swap(nowTitle, t); swap(nowSub, sub); }); }
            @Override public void onVolume(int v) { MAIN.post(() -> showVolume(v)); }
            @Override public void onApps(JSONArray apps) { MAIN.post(() -> renderApps(apps)); }
            @Override public void onSources(JSONArray src) { MAIN.post(() -> sources = src); }
            @Override public void onModel(String model) {
                prefs.edit().putString("name", model.trim()).apply();
                MAIN.post(MainActivity.this::refreshHeader);
            }
        });
        if (d == null || !d.connect()) return false;
        driver = d;
        connectedIp = host;
        prefs.edit().putString("brand_" + host, brand).apply();
        MAIN.post(() -> { refreshHeader(); volBar.setEnabled(d.canSetVolume()); });
        return true;
    }

    String ip() { return prefs.getString("ip", ""); }

    void connect() {
        setStatus(AMBER, "Connecting", true);
        net.execute(() -> {
            if (!ip().isEmpty() && connectTo(ip())) {
                new Thread(this::discover).start();
                return;
            }
            MAIN.post(() -> setStatus(AMBER, "Looking for TVs", true));
            discover();
            String[] target = null;
            synchronized (devices) {
                for (String[] d : devices) if (d[0].equals(ip())) target = d;
                if (target == null && !devices.isEmpty()) target = devices.get(0);
            }
            if (target != null) {
                prefs.edit().putString("ip", target[0]).apply();
                if (connectTo(target[0])) {
                    final String name = target[1];
                    final int count = devices.size();
                    if (count > 1) MAIN.post(() -> toast("Found " + count + " TVs · connected to " + name));
                    return;
                }
            }
            MAIN.post(() -> {
                setStatus(RED, "TV not found · tap to retry", false);
                swap(nowTitle, "TV is off or unreachable");
                swap(nowSub, prefs.getString("mac", "").isEmpty()
                        ? "Turn it on and make sure the phone is on the same Wi-Fi"
                        : "Tap the power button to wake it");
            });
        });
    }

    boolean tryConnect(String host) {
        if (!resumed) return false;
        Mqtt m = new Mqtt(host, PORT, cid, "hisenseservice", "multimqttservice", this);
        try {
            m.connect();
            mqtt = m;
            driver = null;
            connectedIp = host;
            MAIN.post(() -> volBar.setEnabled(true));
            MAIN.post(this::refreshHeader);
            m.subscribe("/remoteapp/mobile/#");
            refresh();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void refresh() {
        action("ui_service", "gettvstate", "");
        action("ui_service", "capability", "");
        action("ui_service", "sourcelist", "");
        action("ui_service", "applist", "");
        action("platform_service", "getvolume", "");
    }

    /** Finds every TV on this Wi-Fi and reads its name. Runs off the main thread. */
    void discover() {
        scanning = true;
        MAIN.post(this::refreshHeader);
        List<String> hosts = new ArrayList<>();
        try {
            String prefix = localPrefix();
            if (prefix != null) {
                ExecutorService pool = Executors.newFixedThreadPool(64);
                final int[] ports = {36669, 8060, 8001, 8002, 3000, 3001};
                for (int i = 1; i < 255; i++) {
                    final String host = prefix + i;
                    pool.execute(() -> {
                        for (int port : ports) {
                            if (TvDriver.open(host, port, 450)) {
                                synchronized (hosts) { hosts.add(host); }
                                return;
                            }
                        }
                    });
                }
                pool.shutdown();
                pool.awaitTermination(25, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {}
        List<String[]> found = new ArrayList<>();
        for (String h : hosts) {
            String[] info = TvDriver.identify(h);   // {brand, name, model}
            if (info == null) continue;
            found.add(new String[]{h, info[1] != null ? info[1] : "TV at " + h, info[2], info[0]});
            prefs.edit().putString("brand_" + h, info[0]).apply();
        }
        String cur = connectedIp;
        boolean listed = false;
        for (String[] d : found) if (d[0].equals(cur)) listed = true;
        if (cur != null && !listed) found.add(0, new String[]{cur, prefs.getString("name", "Your TV"), null, prefs.getString("brand_" + cur, TvDriver.HISENSE)});
        java.util.Collections.sort(found, (a, b) -> Integer.compare(lastOctet(a[0]), lastOctet(b[0])));
        synchronized (devices) { devices.clear(); devices.addAll(found); }
        scanning = false;
        MAIN.post(() -> { refreshHeader(); if (devicesList != null) renderDevices(); });
    }

    static int lastOctet(String ip) {
        try { return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1)); } catch (Exception e) { return 0; }
    }

    /** {friendlyName, modelName} from the TV's media-renderer description, when it has one. */
    String[] describe(String host) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("http://" + host + ":38400/MediaServer/rendererdevicedesc.xml").openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2500);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            String xml = b.toString("UTF-8");
            return new String[]{tag(xml, "friendlyName"), tag(xml, "modelName")};
        } catch (Exception e) {
            return new String[]{null, null};
        }
    }

    static String tag(String xml, String t) {
        int a = xml.indexOf("<" + t + ">");
        if (a < 0) return null;
        int b = xml.indexOf("</" + t + ">", a);
        if (b < 0) return null;
        String v = xml.substring(a + t.length() + 2, b).trim();
        return v.isEmpty() ? null : v;
    }

    /** The connected TV's own name, or its model while the name is unknown. */
    String displayName() {
        String key = connectedIp != null ? connectedIp : ip();
        synchronized (devices) {
            for (String[] d : devices) if (d[0].equals(key) && !d[1].startsWith("TV at")) return d[1];
        }
        return prefs.getString("name", "Your TV");
    }

    void refreshHeader() {
        if (title != null) swap(title, displayName() + "  ▾");
        if (deviceBadge != null) {
            int n = devices.size();
            deviceBadge.setText(String.valueOf(n));
            deviceBadge.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
        }
        if (mqtt != null && connectedIp != null && statusText != null) swap(statusText, "Connected · " + connectedIp);
    }

    /** Switches to another TV from the list. */
    void selectDevice(String[] d) {
        buzz(this, 12);
        if (d[0].equals(connectedIp)) return;
        prefs.edit().putString("ip", d[0]).apply();
        final Mqtt m = mqtt;
        mqtt = null;
        final TvDriver old = driver;
        driver = null;
        connectedIp = null;
        if (m != null) net.execute(m::close);
        if (old != null) net.execute(old::close);
        swap(nowTitle, "Connecting…");
        swap(nowSub, d[1]);
        connect();
    }

    /** Finds the TV on this network by its remote-control port. */
    String scan() {
        try {
            String prefix = localPrefix();
            if (prefix == null) return null;
            final AtomicReference<String> hit = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(40);
            for (int i = 1; i < 255; i++) {
                final String host = prefix + i;
                pool.execute(() -> {
                    if (hit.get() != null) return;
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(host, PORT), 400);
                        hit.compareAndSet(null, host);
                    } catch (Exception ignored) {}
                });
            }
            pool.shutdown();
            pool.awaitTermination(9, TimeUnit.SECONDS);
            return hit.get();
        } catch (Exception e) { return null; }
    }

    String localPrefix() {
        if (!ip().isEmpty()) return ip().substring(0, ip().lastIndexOf('.') + 1);
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    String s = a.getHostAddress();
                    if (a instanceof java.net.Inet4Address && (s.startsWith("192.168.") || s.startsWith("10.") || s.startsWith("172.")))
                        return s.substring(0, s.lastIndexOf('.') + 1);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override public void onConnected() {
        MAIN.post(() -> setStatus(GREEN, "Connected", false));
    }

    @Override public void onDisconnected(String why) {
        mqtt = null;
        connectedIp = null;
        MAIN.post(() -> setStatus(RED, "Disconnected · tap to reconnect", false));
    }

    @Override public void onMessage(String topic, String payload) {
        MAIN.post(() -> handle(topic, payload));
    }

    void handle(String topic, String payload) {
        try {
            if (topic.endsWith("/data/authentication")) {
                startPairing();
            } else if (topic.endsWith("/data/authenticationcode")) {
                pairingShown = false;
                if (new JSONObject(payload).optInt("result") == 1) {
                    toast("Paired with your TV");
                    setStatus(GREEN, "Connected", false);
                    refresh();
                } else {
                    toast("That code didn't match. Try again.");
                    startPairing();
                }
            } else if (topic.endsWith("/ui_service/state") || topic.endsWith("/ui_service/data/state")) {
                showState(new JSONObject(payload));
            } else if (topic.endsWith("/data/capability")) {
                JSONObject o = new JSONObject(payload);
                String model = o.optString("devicemsg", "").split("_")[0];
                if (!model.isEmpty()) {
                    String name = ("his".equals(o.optString("vendorBrand")) ? "Hisense " : "") + model;
                    prefs.edit().putString("name", name).apply();
                    refreshHeader();
                }
            } else if (topic.endsWith("/data/sourcelist")) {
                sources = new JSONArray(payload);
            } else if (topic.endsWith("/data/applist")) {
                renderApps(new JSONArray(payload));
            } else if (topic.contains("volumechange") || topic.endsWith("/data/volume") || topic.endsWith("/ui_service/volume")) {
                int v = new JSONObject(payload).optInt("volume_value", -1);
                if (v >= 0) showVolume(v);
            } else if (topic.endsWith("/actions/tvsleep")) {
                swap(nowTitle, "Standby");
                swap(nowSub, "The TV is going to sleep");
            }
        } catch (Exception ignored) {}
    }

    void showState(JSONObject s) {
        String type = s.optString("statetype");
        String t, sub;
        switch (type) {
            case "livetv":
                t = s.optString("channel_name", "Live TV");
                sub = "Live TV · Channel " + s.optString("channel_num");
                String prog = s.optString("progname");
                if (!prog.isEmpty()) sub = prog + " · Ch " + s.optString("channel_num");
                break;
            case "sourceswitch":
                t = s.optString("displayname", s.optString("sourcename", "Input"));
                sub = s.optString("sourcename") + ("1".equals(s.optString("is_signal")) ? " · signal" : " · no signal");
                break;
            case "remote_launcher": t = "Home screen"; sub = "Pick something to watch"; break;
            case "remote_setting": t = "Settings"; sub = "A settings menu is open"; break;
            case "app": t = s.optString("name", "App"); sub = "App"; break;
            default:
                if (type.startsWith("fake_sleep")) {
                    t = "Standby"; sub = type.endsWith("1") ? "Waking up…" : "Tap power to turn it on";
                } else {
                    t = pretty(s.optString("name", type)); sub = "On the TV now";
                }
        }
        swap(nowTitle, t);
        swap(nowSub, sub);
    }

    void showVolume(int v) {
        if (!userDraggingVol) {
            ValueAnimator a = ValueAnimator.ofInt(volBar.getProgress(), v);
            a.setDuration(250);
            a.addUpdateListener(x -> volBar.setProgress((int) x.getAnimatedValue()));
            a.start();
        }
        volValue.setText(String.valueOf(v));
    }

    void startPairing() {
        if (pairingShown) return;
        pairingShown = true;
        setStatus(AMBER, "Pairing", true);
        try {
            JSONObject o = new JSONObject();
            o.put("app_version", 2); o.put("connect_result", 0); o.put("device_type", "Mobile App");
            action("ui_service", "vidaa_app_connect", o.toString());
        } catch (Exception ignored) {}

        final EditText code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        code.setGravity(Gravity.CENTER);
        code.setTextSize(30);
        code.setLetterSpacing(0.5f);
        code.setHint("0000");
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(this, 24), dp(this, 8), dp(this, 24), 0);
        box.addView(code);
        AlertDialog d = new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle("Pair with your TV")
                .setMessage("Type the 4-digit code showing on the TV.")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Pair", (x, w) -> {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("authNum", code.getText().toString());
                        action("ui_service", "authenticationcode", o.toString());
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Not now", (x, w) -> {
                    pairingShown = false;
                    setStatus(RED, "Not paired · tap to retry", false);
                })
                .create();
        d.show();
        code.requestFocus();
        code.postDelayed(() -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(code, 0), 200);
    }

    // ================================================================ commands

    static String brandLabel(String b) {
        if (b == null) return "TV";
        switch (b) {
            case TvDriver.ROKU: return "Roku";
            case TvDriver.SAMSUNG: return "Samsung";
            case TvDriver.LG: return "LG";
            default: return "Hisense";
        }
    }

    void key(String k) {
        if (driver != null) { buzz(this, 10); driver.key(k); return; }
        buzz(this, 10);
        final Mqtt m = mqtt;
        if (m == null) { notConnected(); return; }
        net.execute(() -> m.publish("/remoteapp/tv/remote_service/" + cid + "/actions/sendkey", k));
    }

    void input(String code) {
        if (driver != null) { driver.text(code); return; }
        final Mqtt m = mqtt;
        if (m == null) { notConnected(); return; }
        net.execute(() -> m.publish("/remoteapp/tv/remote_service/" + cid + "/actions/input", code));
    }

    void action(String service, String name, String payload) {
        final Mqtt m = mqtt;
        if (m == null) return;
        net.execute(() -> m.publish("/remoteapp/tv/" + service + "/" + cid + "/actions/" + name, payload));
    }

    long lastNotConnected;
    void notConnected() {
        if (System.currentTimeMillis() - lastNotConnected < 2000) return;
        lastNotConnected = System.currentTimeMillis();
        toast("Not connected to the TV");
    }

    void power() {
        buzz(this, 25);
        if (driver != null && driver.isConnected()) { driver.key("KEY_POWER"); return; }
        if (mqtt != null) { key("KEY_POWER"); return; }
        String mac = prefs.getString("mac", "");
        if (mac.isEmpty()) { askMac(); return; }
        setStatus(AMBER, "Waking the TV", true);
        new Thread(() -> {
            wakeOnLan(mac);
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            MAIN.post(this::connect);
        }).start();
    }

    void wakeOnLan(String mac) {
        try {
            String[] h = mac.split("[:-]");
            byte[] m = new byte[6];
            for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(h[i], 16);
            byte[] p = new byte[102];
            for (int i = 0; i < 6; i++) p[i] = (byte) 0xFF;
            for (int i = 6; i < 102; i++) p[i] = m[(i - 6) % 6];
            DatagramSocket s = new DatagramSocket();
            s.setBroadcast(true);
            String prefix = localPrefix();
            for (int i = 0; i < 3; i++) {
                if (prefix != null) s.send(new DatagramPacket(p, p.length, InetAddress.getByName(prefix + "255"), 9));
                s.send(new DatagramPacket(p, p.length, InetAddress.getByName("255.255.255.255"), 9));
            }
            s.close();
        } catch (Exception ignored) {}
    }

    void chooseInput() {
        buzz(this, 10);
        if (mqtt == null && driver == null) { notConnected(); return; }
        if (driver != null && sources.length() == 0) { driver.source(new JSONObject()); return; }  // e.g. Samsung: Source key
        final List<JSONObject> items = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (s == null) continue;
            items.add(s);
            String name = s.optString("displayname", s.optString("sourcename"));
            String raw = s.optString("sourcename");
            String label = name.equals(raw) ? name : name + "   " + raw;
            if ("1".equals(s.optString("is_signal"))) label = "●  " + label; else label = "○  " + label;
            labels.add(label);
        }
        if (items.isEmpty()) { toast("Loading inputs…"); if (driver != null) driver.refresh(); else action("ui_service", "sourcelist", ""); return; }
        new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle("Choose input")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    JSONObject s = items.get(which);
                    try {
                        JSONObject o = new JSONObject();
                        o.put("sourceid", s.optString("sourceid"));
                        o.put("sourcename", s.optString("sourcename"));
                        if (driver != null) driver.source(s); else action("ui_service", "changesource", o.toString());
                        toast("Switching to " + s.optString("displayname"));
                    } catch (Exception ignored) {}
                })
                .show();
    }

    void launchApp(JSONObject app) {
        if (driver != null) { buzz(this, 10); driver.launch(app); toast("Opening " + app.optString("name").trim()); return; }
        buzz(this, 10);
        if (mqtt == null) { notConnected(); return; }
        try {
            JSONObject o = new JSONObject();
            o.put("appId", app.optString("appId"));
            o.put("name", app.optString("name"));
            o.put("url", app.optString("url"));
            o.put("urlType", app.optInt("urlType", 37));
            o.put("storeType", app.optInt("storeType", 0));
            action("ui_service", "launchapp", o.toString());
            toast("Opening " + app.optString("name").trim());
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- sheets

    /** A bottom sheet that slides up, with the app's own colours. */
    android.app.Dialog sheet(View content) {
        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(this, 22), dp(this, 10), dp(this, 22), dp(this, 28));
        View grab = new View(this);
        grab.setBackground(shape(TRACK, dp(this, 2), 0, this));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(this, 40), dp(this, 4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = dp(this, 16);
        wrap.addView(grab, glp);
        wrap.addView(content);
        sv.addView(wrap);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SHEET);
        float r = dp(this, 26);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sv.setBackground(bg);
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.setDimAmount(0.55f);
        }
        d.show();
        return d;
    }

    TextView sectionTitle(String t) {
        TextView v = text(this, t.toUpperCase(), 11, FAINT, 700);
        v.setLetterSpacing(0.14f);
        v.setPadding(dp(this, 4), dp(this, 20), 0, dp(this, 8));
        return v;
    }

    LinearLayout group() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(shape(SURFACE, dp(this, 18), STROKE, this));
        return g;
    }

    // ---------------------------------------------------------------- TV list

    LinearLayout devicesList;
    TextView devicesSub;

    void devicesSheet() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = row();
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(this, "TVs", 26, TEXT, 700));
        devicesSub = text(this, "", 13, SUB, 400);
        titles.addView(devicesSub, margins(-2, -2, 0, 4));
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        final TextView refresh = text(this, "↻", 20, CRIMSON, 700);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(ripple(shape(SURFACE, dp(this, 20), STROKE, this)));
        press(refresh, () -> {
            if (scanning) return;
            refresh.animate().rotationBy(360).setDuration(900).start();
            new Thread(this::discover).start();
            renderDevices();
        }, false);
        top.addView(refresh, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        c.addView(top);
        devicesList = new LinearLayout(this);
        devicesList.setOrientation(LinearLayout.VERTICAL);
        devicesList.setLayoutTransition(new LayoutTransition());
        c.addView(devicesList, margins(-1, -2, 0, 16));
        final android.app.Dialog d = sheet(c);
        d.setOnDismissListener(x -> { devicesList = null; devicesSub = null; });
        renderDevices();
        if (devices.isEmpty() && !scanning) new Thread(this::discover).start();
    }

    void renderDevices() {
        if (devicesList == null) return;
        devicesSub.setText(scanning ? "Searching this Wi-Fi…" : devices.size() + " found on this Wi-Fi");
        devicesList.removeAllViews();
        List<String[]> list;
        synchronized (devices) { list = new ArrayList<>(devices); }
        if (list.isEmpty()) {
            TextView empty = text(this, scanning ? "Looking for TVs…"
                    : "No TVs found. Turn the TV on, keep this phone on the same Wi-Fi, then tap ↻.", 14, SUB, 400);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(this, 10), dp(this, 30), dp(this, 10), dp(this, 30));
            devicesList.addView(empty);
            return;
        }
        for (final String[] d : list) {
            boolean connected = d[0].equals(connectedIp);
            LinearLayout r = row();
            r.setPadding(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
            r.setBackground(ripple(shape(SURFACE, dp(this, 18), STROKE, this)));
            FrameLayout ic = new FrameLayout(this);
            ic.setBackground(connected ? gradient(CRIMSON, CRIMSON_DARK, dp(this, 12)) : shape(SURFACE2, dp(this, 12), 0, this));
            ic.addView(new Icon(this, Icon.TV, connected ? Color.WHITE : SUB),
                    new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
            r.addView(ic, new LinearLayout.LayoutParams(dp(this, 46), dp(this, 46)));
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setPadding(dp(this, 14), 0, dp(this, 8), 0);
            t.addView(text(this, d[1], 16, TEXT, 500));
            TextView sub = text(this, brandLabel(d[3]) + " · " + (d[2] != null ? d[2] + " · " : "") + d[0], 12, SUB, 400);
            t.addView(sub, margins(-2, -2, 0, 4));
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            TextView state = text(this, connected ? "Connected" : "Connect", 12, connected ? GREEN : CRIMSON, 500);
            state.setPadding(dp(this, 10), dp(this, 6), dp(this, 10), dp(this, 6));
            state.setBackground(shape(((connected ? GREEN : CRIMSON) & 0x00FFFFFF) | 0x22000000, dp(this, 14), 0, this));
            r.addView(state);
            press(r, () -> { selectDevice(d); renderDevices(); }, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(this, 10);
            devicesList.addView(r, lp);
        }
    }

    // ---------------------------------------------------------------- settings

    void settings() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(text(this, "Settings", 26, TEXT, 700));

        // TV
        c.addView(sectionTitle("TV"));
        LinearLayout tvg = group();
        LinearLayout devRow = row();
        devRow.setPadding(dp(this, 14), dp(this, 14), dp(this, 14), dp(this, 14));
        devRow.setBackground(ripple(shape(0, dp(this, 18), 0, this)));
        LinearLayout dt = new LinearLayout(this);
        dt.setOrientation(LinearLayout.VERTICAL);
        dt.addView(text(this, displayName(), 15, TEXT, 500));
        dt.addView(text(this, connectedIp != null ? "Connected · " + connectedIp : "Not connected", 12, SUB, 400), margins(-2, -2, 0, 3));
        devRow.addView(dt, new LinearLayout.LayoutParams(0, -2, 1));
        devRow.addView(text(this, devices.size() + " found  ›", 12, FAINT, 400));
        tvg.addView(devRow);
        final EditText ipBox = field("TV address (found automatically)", ip(), InputType.TYPE_CLASS_PHONE);
        final EditText macBox = field("TV MAC address, for power-on", prefs.getString("mac", ""), InputType.TYPE_CLASS_TEXT);
        tvg.addView(ipBox);
        tvg.addView(macBox);
        TextView save = text(this, "Save TV details", 14, Color.WHITE, 500);
        save.setGravity(Gravity.CENTER);
        save.setBackground(ripple(gradient(CRIMSON, CRIMSON_DARK, dp(this, 22))));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(this, 44));
        slp.setMargins(dp(this, 14), dp(this, 6), dp(this, 14), dp(this, 14));
        tvg.addView(save, slp);
        c.addView(tvg);

        // Appearance
        c.addView(sectionTitle("Appearance"));
        LinearLayout ag = group();
        LinearLayout modes = row();
        modes.setPadding(dp(this, 10), dp(this, 10), dp(this, 10), dp(this, 10));
        String[] modeNames = {"System", "Light", "Dark"};
        int[] modeIcons = {Icon.HALF, Icon.SUN, Icon.MOON};
        int mode = prefs.getInt("appearance", 0);
        for (int i = 0; i < 3; i++) {
            final int m = i;
            boolean on = mode == i;
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setBackground(ripple(on ? gradient(CRIMSON, CRIMSON_DARK, dp(this, 14)) : shape(SURFACE2, dp(this, 14), 0, this)));
            b.addView(new Icon(this, modeIcons[i], on ? Color.WHITE : TEXT), new LinearLayout.LayoutParams(dp(this, 20), dp(this, 20)));
            b.addView(text(this, modeNames[i], 12, on ? Color.WHITE : TEXT, 500), margins(-2, -2, 0, 6));
            press(b, () -> { prefs.edit().putInt("appearance", m).apply(); recreate(); }, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(this, 66), 1);
            lp.leftMargin = lp.rightMargin = dp(this, 3);
            modes.addView(b, lp);
        }
        ag.addView(modes);
        TextView acl = text(this, "Accent colour", 13, SUB, 500);
        acl.setPadding(dp(this, 14), dp(this, 6), 0, dp(this, 10));
        ag.addView(acl);
        LinearLayout sw = row();
        sw.setPadding(dp(this, 14), 0, dp(this, 14), dp(this, 16));
        int accent = prefs.getInt("accent", 0);
        for (int i = 0; i < ACCENT_MAIN.length; i++) {
            final int a = i;
            FrameLayout dot = new FrameLayout(this);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{ACCENT_MAIN[i], ACCENT_DEEP[i]});
            g.setShape(GradientDrawable.OVAL);
            if (accent == i) g.setStroke(dp(this, 3), TEXT);
            dot.setBackground(g);
            dot.setContentDescription(ACCENT_NAMES[i]);
            if (accent == i) {
                TextView tick = text(this, "✓", 14, Color.WHITE, 700);
                tick.setGravity(Gravity.CENTER);
                dot.addView(tick, new FrameLayout.LayoutParams(-1, -1));
            }
            press(dot, () -> { prefs.edit().putInt("accent", a).apply(); recreate(); }, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36));
            lp.rightMargin = dp(this, 12);
            sw.addView(dot, lp);
        }
        ag.addView(sw);
        c.addView(ag);

        // Feel
        c.addView(sectionTitle("Feel"));
        LinearLayout fg = group();
        LinearLayout hr = row();
        hr.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        hr.addView(text(this, "Vibrate on taps", 15, TEXT, 500), new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.Switch hs = new android.widget.Switch(this);
        hs.setChecked(prefs.getBoolean("haptics", true));
        hs.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        hs.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}}, new int[]{CRIMSON, TRACK}));
        hs.setOnCheckedChangeListener((v, on) -> { prefs.edit().putBoolean("haptics", on).apply(); Ui.haptics = on; });
        hr.addView(hs);
        fg.addView(hr);
        c.addView(fg);

        // About
        c.addView(sectionTitle("About"));
        LinearLayout ab = group();
        LinearLayout abr = row();
        abr.setPadding(dp(this, 14), dp(this, 14), dp(this, 14), dp(this, 14));
        View logo = new View(this);
        logo.setBackground(gradient(CRIMSON, CRIMSON_DARK, dp(this, 10)));
        abr.addView(logo, new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34)));
        LinearLayout abt = new LinearLayout(this);
        abt.setOrientation(LinearLayout.VERTICAL);
        abt.setPadding(dp(this, 12), 0, 0, 0);
        abt.addView(text(this, "Crimson Remote", 15, TEXT, 500));
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        abt.addView(text(this, "Version " + ver + " · by crimson", 12, SUB, 400), margins(-2, -2, 0, 3));
        abr.addView(abt);
        ab.addView(abr);
        c.addView(ab);

        final android.app.Dialog d = sheet(c);
        press(devRow, () -> { d.dismiss(); devicesSheet(); }, false);
        press(save, () -> {
            prefs.edit().putString("ip", ipBox.getText().toString().trim())
                    .putString("mac", macBox.getText().toString().trim().toUpperCase()).apply();
            if (mqtt != null) { mqtt.close(); mqtt = null; }
            d.dismiss();
            toast("Saved");
            connect();
        }, false);
    }

    EditText field(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(type);
        e.setTextColor(TEXT);
        e.setHintTextColor(FAINT);
        e.setTextSize(15);
        e.setBackground(null);
        e.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        return e;
    }

    View splash() {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(BG_BOTTOM);
        f.setClickable(true);
        View glow = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gd.setGradientRadius(dp(this, 220));
        gd.setColors(new int[]{(CRIMSON & 0x00FFFFFF) | 0x66000000, CRIMSON & 0x00FFFFFF});
        glow.setBackground(gd);
        f.addView(glow, new FrameLayout.LayoutParams(dp(this, 440), dp(this, 440), Gravity.CENTER));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout mark = new FrameLayout(this);
        mark.setBackground(gradient(CRIMSON, CRIMSON_DARK, dp(this, 22)));
        mark.setElevation(dp(this, 10));
        mark.addView(new Icon(this, Icon.REMOTE, Color.WHITE), new FrameLayout.LayoutParams(dp(this, 40), dp(this, 40), Gravity.CENTER));
        col.addView(mark, new LinearLayout.LayoutParams(dp(this, 84), dp(this, 84)));
        TextView name = text(this, "CRIMSON", 34, TEXT, 700);
        name.setLetterSpacing(0.05f);
        col.addView(name, margins(-2, -2, 0, 20));
        TextView sub = text(this, "Remote", 14, SUB, 500);
        sub.setLetterSpacing(0.2f);
        col.addView(sub, margins(-2, -2, 0, 10));
        f.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        mark.setScaleX(0.6f); mark.setScaleY(0.6f); mark.setAlpha(0f);
        name.setAlpha(0f); sub.setAlpha(0f); sub.setTranslationY(dp(this, 8));
        glow.setScaleX(0.7f); glow.setScaleY(0.7f); glow.setAlpha(0f);
        mark.animate().scaleX(1).scaleY(1).alpha(1).setDuration(600)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
        glow.animate().scaleX(1.1f).scaleY(1.1f).alpha(1).setDuration(1100).start();
        name.animate().alpha(1).setStartDelay(150).setDuration(500).start();
        ValueAnimator spread = ValueAnimator.ofFloat(0.05f, 0.32f);
        spread.setDuration(900);
        spread.setInterpolator(new android.view.animation.DecelerateInterpolator());
        spread.addUpdateListener(x -> name.setLetterSpacing((float) x.getAnimatedValue()));
        spread.start();
        sub.animate().alpha(1).translationY(0).setStartDelay(350).setDuration(450).start();
        return f;
    }

    void askMac() {
        toast("Add the TV's MAC address to turn it on from the phone");
        settings();
    }

    // ================================================================ layout

    View build() {
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BG_TOP, BG_BOTTOM, BG_BOTTOM}));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        rootFrame.addView(scroll);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(this, 20), dp(this, 14), dp(this, 20), dp(this, 28));
        scroll.addView(root);

        root.addView(header());
        root.addView(nowCard(), margins(-1, -2, 0, 16));
        root.addView(tabs(), margins(-1, dp(this, 46), 0, 18));

        pages = new FrameLayout(this);
        pageViews[0] = remotePage();
        pageViews[1] = touchpadPage();
        pageViews[2] = keyboardPage();
        pageViews[3] = castPage();
        for (View v : pageViews) pages.addView(v, new FrameLayout.LayoutParams(-1, -2));
        root.addView(pages, margins(-1, -2, 0, 18));

        TextView appsTitle = text(this, "APPS", 11, FAINT, 500);
        appsTitle.setLetterSpacing(0.18f);
        root.addView(appsTitle, margins(-2, -2, 0, 22));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        appsRow = new LinearLayout(this);
        appsRow.setOrientation(LinearLayout.HORIZONTAL);
        appsRow.setPadding(0, dp(this, 10), 0, dp(this, 4));
        hs.addView(appsRow);
        root.addView(hs);

        root.addView(bridgeCard(), margins(-1, -2, 0, 24));

        TextView foot = text(this, "Crimson Remote", 11, FAINT, 400);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot, margins(-1, -2, 0, 24));

        // Splash with the Crimson name, then everything rises in gently.
        root.setAlpha(0f);
        root.setTranslationY(dp(this, 18));
        final View splash = splash();
        rootFrame.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        MAIN.postDelayed(() -> {
            splash.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(420)
                    .withEndAction(() -> rootFrame.removeView(splash)).start();
            root.animate().alpha(1f).translationY(0).setStartDelay(120).setDuration(480)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }, 1400);
        return rootFrame;
    }

    View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text(this, "CRIMSON", 12, CRIMSON, 700);
        brand.setLetterSpacing(0.32f);
        left.addView(brand);
        title = text(this, prefs.getString("name", "Your TV") + "  ▾", 24, TEXT, 700);
        title.setPadding(0, dp(this, 6), 0, 0);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        press(title, this::devicesSheet, false);
        left.addView(title);
        h.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        FrameLayout tvBtn = new FrameLayout(this);
        tvBtn.setBackground(ripple(shape(SURFACE, dp(this, 20), STROKE, this)));
        tvBtn.addView(new Icon(this, Icon.TV, SUB), new FrameLayout.LayoutParams(dp(this, 20), dp(this, 20), Gravity.CENTER));
        deviceBadge = text(this, "", 10, Color.WHITE, 700);
        deviceBadge.setGravity(Gravity.CENTER);
        deviceBadge.setBackground(shape(CRIMSON, dp(this, 8), 0, this));
        deviceBadge.setVisibility(View.GONE);
        tvBtn.addView(deviceBadge, new FrameLayout.LayoutParams(dp(this, 16), dp(this, 16), Gravity.TOP | Gravity.END));
        tvBtn.setContentDescription("TVs");
        press(tvBtn, this::devicesSheet, false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40));
        blp.rightMargin = dp(this, 10);
        h.addView(tvBtn, blp);

        FrameLayout gear = new FrameLayout(this);
        gear.setBackground(ripple(shape(SURFACE, dp(this, 20), STROKE, this)));
        gear.addView(new Icon(this, Icon.GEAR, SUB), new FrameLayout.LayoutParams(dp(this, 20), dp(this, 20), Gravity.CENTER));
        gear.setContentDescription("Settings");
        press(gear, this::settings, false);
        h.addView(gear, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(h);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(this, 10), dp(this, 6), dp(this, 12), dp(this, 6));
        pill.setBackground(ripple(shape(SURFACE, dp(this, 16), STROKE, this)));
        statusDot = new View(this);
        statusDot.setBackground(shape(AMBER, dp(this, 4), 0, this));
        pill.addView(statusDot, new LinearLayout.LayoutParams(dp(this, 8), dp(this, 8)));
        statusText = text(this, "Connecting", 12, SUB, 500);
        statusText.setPadding(dp(this, 8), 0, 0, 0);
        pill.addView(statusText);
        pill.setOnClickListener(v -> { if (mqtt == null) connect(); else devicesSheet(); });
        wrap.addView(pill, margins(-2, -2, 0, 10));
        return wrap;
    }

    View nowCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(this, 18), dp(this, 16), dp(this, 18), dp(this, 12));
        GradientDrawable bg = gradient(blend(SURFACE, CRIMSON, dark ? 0.16f : 0.12f), SURFACE, dp(this, 24));
        bg.setStroke(dp(this, 1), blend(STROKE, CRIMSON, 0.3f));
        card.setBackground(bg);

        TextView label = text(this, "ON THE TV", 10, CRIMSON, 700);
        label.setLetterSpacing(0.22f);
        card.addView(label);
        nowTitle = text(this, "Connecting…", 20, TEXT, 700);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(nowTitle, margins(-1, -2, 0, 8));
        nowSub = text(this, " ", 13, SUB, 400);
        card.addView(nowSub, margins(-1, -2, 0, 5));

        LinearLayout volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        volRow.addView(new SpeakerIcon(this), new LinearLayout.LayoutParams(dp(this, 20), dp(this, 20)));
        volBar = new SeekBar(this);
        volBar.setMax(100);
        volBar.setProgressTintList(android.content.res.ColorStateList.valueOf(CRIMSON));
        volBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        volBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(TRACK));
        volBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                volValue.setText(String.valueOf(p));
                if (System.currentTimeMillis() - lastVolSent > 120) {
                    lastVolSent = System.currentTimeMillis();
                    if (driver != null) driver.setVolume(p); else action("platform_service", "changevolume", String.valueOf(p));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userDraggingVol = true; buzz(MainActivity.this, 8); }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userDraggingVol = false;
                if (driver != null) driver.setVolume(s.getProgress()); else action("platform_service", "changevolume", String.valueOf(s.getProgress()));
            }
        });
        volRow.addView(volBar, new LinearLayout.LayoutParams(0, dp(this, 36), 1));
        volValue = text(this, "–", 13, TEXT, 500);
        volValue.setGravity(Gravity.END);
        volRow.addView(volValue, new LinearLayout.LayoutParams(dp(this, 28), -2));
        card.addView(volRow, margins(-1, -2, 0, 10));
        return card;
    }

    View tabs() {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(shape(SURFACE, dp(this, 23), STROKE, this));
        f.setPadding(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
        tabIndicator = new View(this);
        tabIndicator.setBackground(gradient(CRIMSON, CRIMSON_DARK, dp(this, 19)));
        f.addView(tabIndicator, new FrameLayout.LayoutParams(0, -1));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Remote", "Touchpad", "Keys", "Cast"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = text(this, names[i], 14, SUB, 500);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> { buzz(this, 8); showTab(idx, true); });
            tabLabels[i] = t;
            row.addView(t, new LinearLayout.LayoutParams(0, -1, 1));
        }
        f.addView(row, new FrameLayout.LayoutParams(-1, -1));
        f.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = (r - l - dp(this, 8)) / 4;
            ViewGroup.LayoutParams lp = tabIndicator.getLayoutParams();
            if (lp.width != w) {
                lp.width = w;
                tabIndicator.setLayoutParams(lp);
                tabIndicator.setTranslationX(currentTab * w);
            }
        });
        return f;
    }

    void showTab(int idx, boolean animate) {
        int from = currentTab;
        currentTab = idx;
        int w = tabIndicator.getWidth();
        if (animate) {
            tabIndicator.animate().translationX(idx * w).setDuration(280)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(0.9f)).start();
        } else tabIndicator.setTranslationX(idx * w);
        for (int i = 0; i < 4; i++) tabLabels[i].setTextColor(i == idx ? Color.WHITE : SUB);

        for (int i = 0; i < 4; i++) {
            final View v = pageViews[i];
            if (i == idx) {
                v.setVisibility(View.VISIBLE);
                if (animate) {
                    v.setAlpha(0f);
                    v.setTranslationX((idx > from ? 1 : -1) * dp(this, 30));
                    v.animate().alpha(1f).translationX(0).setDuration(260)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                }
            } else v.setVisibility(View.GONE);
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (idx == 2 && typeBox != null) {
            typeBox.requestFocus();
            typeBox.postDelayed(() -> imm.showSoftInput(typeBox, 0), 250);
        } else if (typeBox != null) {
            imm.hideSoftInputFromWindow(typeBox.getWindowToken(), 0);
        }
    }

    // ---------------------------------------------------------------- Remote page

    View remotePage() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setLayoutTransition(new LayoutTransition());

        LinearLayout top = row();
        View pw = round(new PowerIcon(this), 0, dp(this, 62), true);
        press(pw, this::power, false);
        top.addView(pw);
        top.addView(flex());
        TextView input = text(this, "Input", 15, TEXT, 500);
        input.setGravity(Gravity.CENTER);
        input.setBackground(ripple(shape(SURFACE2, dp(this, 26), STROKE, this)));
        press(input, this::chooseInput, false);
        top.addView(input, new LinearLayout.LayoutParams(dp(this, 116), dp(this, 52)));
        top.addView(flex());
        View mute = round(new MuteIcon(this), SURFACE2, dp(this, 62), false);
        press(mute, () -> key("KEY_MUTE"), false);
        top.addView(mute);
        p.addView(top);

        LinearLayout mid = row();
        mid.addView(rocker("VOL", "KEY_VOLUMEUP", "KEY_VOLUMEDOWN"));
        mid.addView(flex());
        mid.addView(dpad());
        mid.addView(flex());
        mid.addView(rocker("CH", "KEY_CHANNELUP", "KEY_CHANNELDOWN"));
        p.addView(mid, margins(-1, -2, 0, 22));

        LinearLayout nav = row();
        String[][] navKeys = {{"Back", "KEY_RETURNS"}, {"Home", "KEY_HOME"}, {"Menu", "KEY_MENU"}, {"Exit", "KEY_EXIT"}};
        for (String[] k : navKeys) {
            TextView t = text(this, k[0], 13, TEXT, 500);
            t.setGravity(Gravity.CENTER);
            t.setBackground(ripple(shape(SURFACE2, dp(this, 24), STROKE, this)));
            press(t, () -> key(k[1]), false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(this, 48), 1);
            lp.leftMargin = lp.rightMargin = dp(this, 4);
            nav.addView(t, lp);
        }
        p.addView(nav, margins(-1, -2, 0, 22));

        LinearLayout media = new LinearLayout(this);
        media.setOrientation(LinearLayout.HORIZONTAL);
        media.setPadding(dp(this, 6), dp(this, 6), dp(this, 6), dp(this, 6));
        media.setBackground(shape(SURFACE, dp(this, 28), STROKE, this));
        media.addView(mediaBtn("⏮", "KEY_BACK"), weight());
        final TextView pp = text(this, "❚❚", 16, TEXT, 500);
        pp.setGravity(Gravity.CENTER);
        pp.setBackground(ripple(shape(0, dp(this, 24), 0, this)));
        press(pp, () -> {
            key(playing ? "KEY_PAUSE" : "KEY_PLAY");
            playing = !playing;
            swap(pp, playing ? "❚❚" : "▶");
        }, false);
        media.addView(pp, weight());
        media.addView(mediaBtn("⏭", "KEY_FORWARDS"), weight());
        p.addView(media, margins(-1, dp(this, 58), 0, 14));

        // Expandable extras: numbers, colour keys, info, subtitles.
        final LinearLayout extra = new LinearLayout(this);
        extra.setOrientation(LinearLayout.VERTICAL);
        extra.setVisibility(View.GONE);
        String[][] nums = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"Info", "0", "Subs"}};
        for (String[] r : nums) {
            LinearLayout rr = row();
            for (String n : r) {
                String k = n.equals("Info") ? "KEY_INFO" : n.equals("Subs") ? "KEY_SUBTITLE" : "KEY_" + n;
                TextView t = text(this, n, n.length() == 1 ? 20 : 13, TEXT, 500);
                t.setGravity(Gravity.CENTER);
                t.setBackground(ripple(shape(SURFACE2, dp(this, 18), STROKE, this)));
                press(t, () -> key(k), false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(this, 50), 1);
                lp.setMargins(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
                rr.addView(t, lp);
            }
            extra.addView(rr);
        }
        LinearLayout colours = row();
        int[] cs = {0xFFEF4444, 0xFF22C55E, 0xFFEAB308, 0xFF3B82F6};
        String[] ck = {"KEY_RED", "KEY_GREEN", "KEY_YELLOW", "KEY_BLUE"};
        for (int i = 0; i < 4; i++) {
            View c = new View(this);
            c.setBackground(ripple(shape(cs[i], dp(this, 10), 0, this)));
            final String k = ck[i];
            press(c, () -> key(k), false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(this, 22), 1);
            lp.setMargins(dp(this, 8), dp(this, 10), dp(this, 8), 0);
            colours.addView(c, lp);
        }
        extra.addView(colours);

        final TextView more = text(this, "More buttons  ⌄", 13, SUB, 500);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, dp(this, 12), 0, dp(this, 6));
        more.setOnClickListener(v -> {
            boolean open = extra.getVisibility() != View.VISIBLE;
            extra.setVisibility(open ? View.VISIBLE : View.GONE);
            more.setText(open ? "Fewer buttons  ⌃" : "More buttons  ⌄");
            buzz(this, 8);
        });
        p.addView(more);
        p.addView(extra);
        return p;
    }

    View dpad() {
        int size = dp(this, 194);
        FrameLayout f = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{RAISED, SURFACE});
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(dp(this, 1), STROKE);
        f.setBackground(ring);
        String[][] arrows = {{"▲", "KEY_UP"}, {"▼", "KEY_DOWN"}, {"◀", "KEY_LEFT"}, {"▶", "KEY_RIGHT"}};
        int[] g = {Gravity.TOP | Gravity.CENTER_HORIZONTAL, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                Gravity.START | Gravity.CENTER_VERTICAL, Gravity.END | Gravity.CENTER_VERTICAL};
        for (int i = 0; i < 4; i++) {
            TextView t = text(this, arrows[i][0], 15, SUB, 400);
            t.setGravity(Gravity.CENTER);
            GradientDrawable o = new GradientDrawable();
            o.setShape(GradientDrawable.OVAL);
            o.setColor(0);
            t.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(RIPPLE), o, null));
            final String k = arrows[i][1];
            press(t, () -> key(k), true);
            f.addView(t, new FrameLayout.LayoutParams(dp(this, 60), dp(this, 60), g[i]));
        }
        TextView ok = text(this, "OK", 17, Color.WHITE, 700);
        ok.setGravity(Gravity.CENTER);
        GradientDrawable okBg = gradient(CRIMSON, CRIMSON_DARK, dp(this, 38));
        ok.setBackground(ripple(okBg));
        ok.setElevation(dp(this, 6));
        if (Build.VERSION.SDK_INT >= 28) { ok.setOutlineSpotShadowColor(CRIMSON); ok.setOutlineAmbientShadowColor(CRIMSON); }
        press(ok, () -> key("KEY_OK"), false);
        f.addView(ok, new FrameLayout.LayoutParams(dp(this, 76), dp(this, 76), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return f;
    }

    View rocker(String label, String up, String down) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.CENTER_HORIZONTAL);
        r.setBackground(shape(SURFACE, dp(this, 30), STROKE, this));
        TextView plus = text(this, "+", 24, TEXT, 400);
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(ripple(shape(0, dp(this, 30), 0, this)));
        press(plus, () -> key(up), true);
        r.addView(plus, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView l = text(this, label, 10, FAINT, 700);
        l.setGravity(Gravity.CENTER);
        l.setLetterSpacing(0.2f);
        r.addView(l, new LinearLayout.LayoutParams(-1, -2));
        TextView minus = text(this, "−", 24, TEXT, 400);
        minus.setGravity(Gravity.CENTER);
        minus.setBackground(ripple(shape(0, dp(this, 30), 0, this)));
        press(minus, () -> key(down), true);
        r.addView(minus, new LinearLayout.LayoutParams(-1, 0, 1));
        r.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 58), dp(this, 178)));
        return r;
    }

    View mediaBtn(String glyph, String k) {
        TextView t = text(this, glyph, 18, TEXT, 400);
        t.setGravity(Gravity.CENTER);
        t.setBackground(ripple(shape(0, dp(this, 24), 0, this)));
        press(t, () -> key(k), false);
        return t;
    }

    View round(View child, int color, int size, boolean crimson) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable bg = crimson ? gradient(CRIMSON, CRIMSON_DARK, size / 2f) : shape(color, size / 2f, STROKE, this);
        f.setBackground(ripple(bg));
        f.setElevation(dp(this, crimson ? 8 : 2));
        if (crimson && Build.VERSION.SDK_INT >= 28) { f.setOutlineSpotShadowColor(CRIMSON); f.setOutlineAmbientShadowColor(CRIMSON); }
        f.addView(child, new FrameLayout.LayoutParams(dp(this, 26), dp(this, 26), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return f;
    }

    // ---------------------------------------------------------------- Touchpad page

    View touchpadPage() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        TouchpadView pad = new TouchpadView(this);
        pad.setListener(new TouchpadView.Listener() {
            @Override public void onStep(String k) { key(k); }
            @Override public void onTap() { key("KEY_OK"); }
        });
        p.addView(pad, new LinearLayout.LayoutParams(-1, dp(this, 330)));

        TextView hint = text(this, "Swipe to move · Tap to select", 12, FAINT, 400);
        hint.setGravity(Gravity.CENTER);
        p.addView(hint, margins(-1, -2, 0, 10));

        LinearLayout r = row();
        final TextView pointer = chip("Pointer mode");
        press(pointer, () -> {
            key("KEY_LEFTMOUSEKEYS");
            pointerMode = !pointerMode;
            pointer.setBackground(ripple(pointerMode
                    ? gradient(CRIMSON, CRIMSON_DARK, dp(this, 22))
                    : shape(SURFACE2, dp(this, 22), STROKE, this)));
            toast(pointerMode ? "Pointer on: swipe moves the cursor" : "Pointer off");
        }, false);
        r.addView(pointer, chipLp(1));
        TextView back = chip("Back");
        press(back, () -> key("KEY_RETURNS"), false);
        r.addView(back, chipLp(1));
        TextView home = chip("Home");
        press(home, () -> key("KEY_HOME"), false);
        r.addView(home, chipLp(1));
        p.addView(r, margins(-1, -2, 0, 14));
        return p;
    }

    // ---------------------------------------------------------------- Keyboard page

    // ---------------------------------------------------------------- Cast page

    static final int PICK_MEDIA = 41;
    Caster caster;
    TextView castStatus;

    View castPage() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);

        p.addView(castCard(Icon.PLAY, "Photos, videos & music",
                "Send a file from this phone to the TV over Wi-Fi.", "Choose a file", () -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*", "audio/*"});
                    startActivityForResult(i, PICK_MEDIA);
                }));

        LinearLayout controls = row();
        String[][] c = {{"Play", "play"}, {"Pause", "pause"}, {"Stop", "stop"}};
        for (String[] b : c) {
            TextView t = chip(b[0]);
            press(t, () -> castControl(b[1]), false);
            controls.addView(t, chipLp(1));
        }
        p.addView(controls, margins(-1, -2, 0, 10));
        castStatus = text(this, "Nothing casting", 12, FAINT, 400);
        castStatus.setGravity(Gravity.CENTER);
        p.addView(castStatus, margins(-1, -2, 0, 8));

        p.addView(castCard(Icon.MIRROR, "Mirror this phone",
                "Shows your phone screen on the TV. On the TV open Screen Share (Anyview Cast) first.",
                "Open screen cast", this::openScreenCast), margins(-1, -2, 0, 16));

        p.addView(castCard(Icon.DESKTOP, "Crimson Desktop",
                "A desktop on the TV while your phone becomes the trackpad. Start mirroring first.",
                "Open desktop", this::openDesktop), margins(-1, -2, 0, 12));
        return p;
    }

    View castCard(int icon, String title, String desc, String action, Runnable run) {
        LinearLayout card = group();
        card.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
        LinearLayout top = row();
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(shape(CRIMSON_SOFT, dp(this, 12), 0, this));
        ic.addView(new Icon(this, icon, CRIMSON), new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
        top.addView(ic, new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(this, 14), 0, 0, 0);
        t.addView(text(this, title, 16, TEXT, 500));
        TextView d = text(this, desc, 12, SUB, 400);
        d.setLineSpacing(0, 1.15f);
        t.addView(d, margins(-1, -2, 0, 4));
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(top);
        TextView go = text(this, action, 14, Color.WHITE, 500);
        go.setGravity(Gravity.CENTER);
        go.setBackground(ripple(gradient(CRIMSON, CRIMSON_DARK, dp(this, 22))));
        press(go, run, false);
        card.addView(go, margins(-1, dp(this, 44), 0, 14));
        return card;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_MEDIA || res != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        final String tv = connectedIp != null ? connectedIp : ip();
        if (tv.isEmpty()) { toast("Connect to a TV first"); return; }
        if (caster == null) caster = new Caster(this);
        castStatus.setText("Sending to the TV…");
        new Thread(() -> {
            try {
                caster.cast(tv, uri);
                String name = caster.displayName(uri);
                MAIN.post(() -> { castStatus.setText("Casting " + caster.kind() + ": " + name); toast("Casting to the TV"); });
            } catch (Exception e) {
                MAIN.post(() -> { castStatus.setText(String.valueOf(e.getMessage())); toast(String.valueOf(e.getMessage())); });
            }
        }).start();
    }

    void castControl(String what) {
        if (caster == null) { toast("Choose a file to cast first"); return; }
        buzz(this, 10);
        new Thread(() -> {
            try {
                if (what.equals("play")) caster.play(); else if (what.equals("pause")) caster.pause(); else caster.stop();
                if (what.equals("stop")) MAIN.post(() -> castStatus.setText("Nothing casting"));
            } catch (Exception e) {
                MAIN.post(() -> toast(String.valueOf(e.getMessage())));
            }
        }).start();
    }

    /** Opens the phone's own screen-cast (Miracast) page. */
    void openScreenCast() {
        String[] actions = {"android.settings.CAST_SETTINGS", "android.settings.WIFI_DISPLAY_SETTINGS"};
        for (String a : actions) {
            try { startActivity(new Intent(a)); return; } catch (Exception ignored) {}
        }
        toast("This phone has no built-in screen cast");
    }

    void openDesktop() {
        android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        android.view.Display[] ds = dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (ds.length == 0) {
            toast("Start mirroring to the TV first, then open the desktop");
            openScreenCast();
            return;
        }
        startActivity(new Intent(this, DesktopActivity.class));
    }

    View keyboardPage() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);

        TextView info = text(this, "Select a search box on the TV, then type here.", 13, SUB, 400);
        p.addView(info);

        typeBox = new EditText(this);
        typeBox.setTextColor(TEXT);
        typeBox.setHintTextColor(FAINT);
        typeBox.setHint("Type to send to the TV…");
        typeBox.setTextSize(18);
        typeBox.setPadding(dp(this, 18), dp(this, 16), dp(this, 18), dp(this, 16));
        typeBox.setBackground(shape(SURFACE, dp(this, 20), STROKE, this));
        typeBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        typeBox.setImeOptions(EditorInfo.IME_ACTION_SEND);
        typeBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { syncTyped(e.toString()); }
        });
        typeBox.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEND || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                input("Lit_ENTER");
                buzz(this, 15);
                return true;
            }
            return false;
        });
        p.addView(typeBox, margins(-1, -2, 0, 12));

        LinearLayout r = row();
        TextView clear = chip("Clear");
        press(clear, () -> typeBox.setText(""), false);
        r.addView(clear, chipLp(1));
        TextView bksp = chip("⌫");
        press(bksp, () -> input("Lit_BACKSPACE"), true);
        r.addView(bksp, chipLp(1));
        TextView enter = chip("Enter");
        enter.setTextColor(Color.WHITE);
        enter.setBackground(ripple(gradient(CRIMSON, CRIMSON_DARK, dp(this, 22))));
        press(enter, () -> input("Lit_ENTER"), false);
        r.addView(enter, chipLp(1));
        p.addView(r, margins(-1, -2, 0, 12));
        return p;
    }

    /** Sends only what changed: backspaces for removed text, letters for new text. */
    void syncTyped(String now) {
        int common = 0;
        while (common < now.length() && common < typed.length() && now.charAt(common) == typed.charAt(common)) common++;
        for (int i = common; i < typed.length(); i++) input("Lit_BACKSPACE");
        for (int i = common; i < now.length(); i++) {
            char c = now.charAt(i);
            input(c == ' ' ? "Lit_SPACE" : "Lit_" + c);
        }
        typed = now;
    }

    // ---------------------------------------------------------------- apps

    void renderApps(JSONArray list) {
        appsRow.removeAllViews();
        if (list == null) {
            list = new JSONArray();
            String[][] d = {{"YouTube", "youtube", "3"}, {"Netflix", "netflix", "1"}, {"Prime Video", "amazon", ""}};
            for (String[] a : d) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", a[0]); o.put("url", a[1]); o.put("appId", a[2]);
                    o.put("urlType", 37); o.put("storeType", 0);
                    list.put(o);
                } catch (Exception ignored) {}
            }
        }
        int[] tints = {0xFFE11D48, 0xFFF97316, 0xFF8B5CF6, 0xFF06B6D4, 0xFF22C55E, 0xFFEAB308, 0xFF3B82F6, 0xFFEC4899};
        for (int i = 0; i < list.length() && i < 16; i++) {
            final JSONObject app = list.optJSONObject(i);
            if (app == null) continue;
            String name = app.optString("name").trim().replaceAll("\\s+\\d+(\\.\\d+)*$", "");
            if (name.isEmpty()) continue;

            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(dp(this, 6), dp(this, 6), dp(this, 6), dp(this, 6));
            tile.setBackground(ripple(shape(0, dp(this, 18), 0, this)));

            // Letter badge first; swapped for the real logo as soon as it loads.
            final FrameLayout badgeBox = new FrameLayout(this);
            final TextView badge = text(this, name.substring(0, 1).toUpperCase(), 22, Color.WHITE, 700);
            badge.setGravity(Gravity.CENTER);
            int c = tints[Math.abs(name.hashCode()) % tints.length];
            badgeBox.setBackground(gradient(c, darker(c), dp(this, 18)));
            badgeBox.setClipToOutline(true);
            badgeBox.addView(badge, new FrameLayout.LayoutParams(-1, -1));
            final android.widget.ImageView logo = new android.widget.ImageView(this);
            logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            int pad = dp(this, 11);
            logo.setPadding(pad, pad, pad, pad);
            logo.setAlpha(0f);
            badgeBox.addView(logo, new FrameLayout.LayoutParams(-1, -1));
            tile.addView(badgeBox, new LinearLayout.LayoutParams(dp(this, 58), dp(this, 58)));
            final String domain = AppIcons.domain(name, app.optString("url"));
            final String tvIcon = app.optString("icon", "");
            if (domain != null || !tvIcon.isEmpty()) {
                new Thread(() -> {
                    // The TV's own icon when it gives one (Roku, LG), otherwise the service's site icon.
                    android.graphics.Bitmap first = tvIcon.isEmpty() ? null : AppIcons.loadUrl(tvIcon);
                    final android.graphics.Bitmap bm = first != null ? first : AppIcons.load(domain);
                    if (bm == null) return;
                    MAIN.post(() -> {
                        logo.setImageBitmap(bm);
                        badgeBox.setBackground(shape(Color.WHITE, dp(this, 18), 0, this));
                        badge.animate().alpha(0f).setDuration(180).start();
                        logo.animate().alpha(1f).setDuration(260).start();
                    });
                }).start();
            }
            TextView label = text(this, name, 11, SUB, 500);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(label, new LinearLayout.LayoutParams(dp(this, 70), -2));
            ((LinearLayout.LayoutParams) label.getLayoutParams()).topMargin = dp(this, 6);
            press(tile, () -> launchApp(app), false);
            appsRow.addView(tile);

            tile.setAlpha(0f);
            tile.setTranslationY(dp(this, 10));
            tile.animate().alpha(1f).translationY(0).setStartDelay(i * 35L).setDuration(260).start();
        }
    }

    // ---------------------------------------------------------------- iPhone bridge

    View bridgeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(this, 18), dp(this, 16), dp(this, 18), dp(this, 16));
        card.setBackground(shape(SURFACE, dp(this, 22), STROKE, this));
        card.setLayoutTransition(new LayoutTransition());

        LinearLayout top = row();
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(this, "IPHONE BRIDGE", 10, CRIMSON, 700);
        label.setLetterSpacing(0.22f);
        txt.addView(label);
        TextView desc = text(this, "Use this remote from your iPhone or any browser on the same Wi-Fi.", 13, SUB, 400);
        desc.setLineSpacing(0, 1.15f);
        txt.addView(desc, margins(-1, -2, 0, 6));
        top.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));

        final FrameLayout toggle = new FrameLayout(this);
        final View knob = new View(this);
        knob.setBackground(shape(Color.WHITE, dp(this, 11), 0, this));
        knob.setElevation(dp(this, 2));
        toggle.addView(knob, new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER_VERTICAL));
        toggle.setPadding(dp(this, 3), 0, dp(this, 3), 0);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(this, 50), dp(this, 28));
        tlp.leftMargin = dp(this, 12);
        top.addView(toggle, tlp);
        card.addView(top);

        final LinearLayout on = new LinearLayout(this);
        on.setOrientation(LinearLayout.VERTICAL);
        final TextView addr = text(this, "", 17, TEXT, 700);
        addr.setTextIsSelectable(true);
        on.addView(addr, margins(-1, -2, 0, 14));
        TextView how = text(this, "On the iPhone, open this in Safari, tap Share, then Add to Home Screen.", 12, FAINT, 400);
        how.setLineSpacing(0, 1.15f);
        on.addView(how, margins(-1, -2, 0, 6));
        LinearLayout btns = row();
        TextView copy = chip("Copy link");
        press(copy, () -> {
            String a = Bridge.address();
            if (a == null) { toast("Connect the phone to Wi-Fi first"); return; }
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(android.content.ClipData.newPlainText("Crimson", a));
            toast("Link copied");
        }, false);
        btns.addView(copy, chipLp(1));
        TextView share = chip("Share");
        press(share, () -> {
            String a = Bridge.address();
            if (a == null) { toast("Connect the phone to Wi-Fi first"); return; }
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_TEXT, "Crimson Remote: " + a);
            startActivity(Intent.createChooser(s, "Send the remote link"));
        }, false);
        btns.addView(share, chipLp(1));
        on.addView(btns, margins(-1, -2, 0, 12));
        card.addView(on);

        final Runnable paint = () -> {
            boolean enabled = prefs.getBoolean("bridge_on", false);
            toggle.setBackground(enabled ? gradient(CRIMSON, CRIMSON_DARK, dp(this, 14)) : shape(SURFACE2, dp(this, 14), STROKE, this));
            knob.animate().translationX(enabled ? dp(this, 22) : 0).setDuration(240)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
            on.setVisibility(enabled ? View.VISIBLE : View.GONE);
            String a = Bridge.address();
            addr.setText(a == null ? "Connect the phone to Wi-Fi" : a);
        };
        press(toggle, () -> {
            boolean enabled = !prefs.getBoolean("bridge_on", false);
            prefs.edit().putBoolean("bridge_on", enabled).apply();
            if (enabled) BridgeService.start(this); else BridgeService.stop(this);
            buzz(this, 15);
            paint.run();
        }, false);
        paint.run();
        if (prefs.getBoolean("bridge_on", false) && !BridgeService.running) BridgeService.start(this);
        return card;
    }

    // ================================================================ helpers

    void setStatus(int color, String msg, boolean busy) {
        if (statusDot == null) return;
        statusDot.setBackground(shape(color, dp(this, 4), 0, this));
        if (pulse != null) { pulse.cancel(); pulse = null; statusDot.setAlpha(1f); }
        if (busy) pulse = Ui.pulse(statusDot);
        swap(statusText, msg);
    }

    void swap(TextView t, String s) { if (t != null) swapText(t, s); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    TextView chip(String s) {
        TextView t = text(this, s, 13, TEXT, 500);
        t.setGravity(Gravity.CENTER);
        t.setBackground(ripple(shape(SURFACE2, dp(this, 22), STROKE, this)));
        return t;
    }

    LinearLayout.LayoutParams chipLp(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(this, 46), w);
        lp.leftMargin = lp.rightMargin = dp(this, 4);
        return lp;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    View flex() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        return v;
    }

    LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -1, 1); }

    LinearLayout.LayoutParams margins(int w, int h, int l, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.topMargin = dp(this, top);
        return lp;
    }

    static int darker(int c) {
        return Color.rgb((int) (Color.red(c) * 0.62), (int) (Color.green(c) * 0.62), (int) (Color.blue(c) * 0.62));
    }

    static String pretty(String s) {
        if (s.isEmpty()) return "Watching";
        s = s.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ================================================================ icons

    static class PowerIcon extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        PowerIcon(Context c) {
            super(c);
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(c, 2.6f));
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = p.getStrokeWidth();
            c.drawArc(new RectF(s, s + 1, w - s, h - s + 1), -55, 290, false, p);
            c.drawLine(w / 2, s - 1, w / 2, h * 0.48f, p);
        }
    }

    static class MuteIcon extends View {
        final Paint f = new Paint(Paint.ANTI_ALIAS_FLAG), l = new Paint(Paint.ANTI_ALIAS_FLAG);
        MuteIcon(Context c) {
            super(c);
            f.setColor(TEXT);
            l.setColor(TEXT);
            l.setStyle(Paint.Style.STROKE);
            l.setStrokeCap(Paint.Cap.ROUND);
            l.setStrokeWidth(dp(c, 2.2f));
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            android.graphics.Path sp = new android.graphics.Path();
            sp.moveTo(w * 0.08f, h * 0.36f); sp.lineTo(w * 0.30f, h * 0.36f); sp.lineTo(w * 0.55f, h * 0.12f);
            sp.lineTo(w * 0.55f, h * 0.88f); sp.lineTo(w * 0.30f, h * 0.64f); sp.lineTo(w * 0.08f, h * 0.64f); sp.close();
            c.drawPath(sp, f);
            c.drawLine(w * 0.70f, h * 0.36f, w * 0.94f, h * 0.64f, l);
            c.drawLine(w * 0.94f, h * 0.36f, w * 0.70f, h * 0.64f, l);
        }
    }

    static class SpeakerIcon extends View {
        final Paint f = new Paint(Paint.ANTI_ALIAS_FLAG), l = new Paint(Paint.ANTI_ALIAS_FLAG);
        SpeakerIcon(Context c) {
            super(c);
            f.setColor(SUB);
            l.setColor(SUB);
            l.setStyle(Paint.Style.STROKE);
            l.setStrokeCap(Paint.Cap.ROUND);
            l.setStrokeWidth(dp(c, 1.8f));
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            android.graphics.Path sp = new android.graphics.Path();
            sp.moveTo(w * 0.05f, h * 0.38f); sp.lineTo(w * 0.25f, h * 0.38f); sp.lineTo(w * 0.48f, h * 0.16f);
            sp.lineTo(w * 0.48f, h * 0.84f); sp.lineTo(w * 0.25f, h * 0.62f); sp.lineTo(w * 0.05f, h * 0.62f); sp.close();
            c.drawPath(sp, f);
            c.drawArc(new RectF(w * 0.40f, h * 0.30f, w * 0.75f, h * 0.70f), -50, 100, false, l);
            c.drawArc(new RectF(w * 0.40f, h * 0.15f, w * 0.95f, h * 0.85f), -50, 100, false, l);
        }
    }

    /** Small line icons drawn in code so they match on every phone. */
    static class Icon extends View {
        static final int TV = 0, GEAR = 1, SUN = 2, MOON = 3, HALF = 4, REMOTE = 5, PLAY = 6, MIRROR = 7, DESKTOP = 8;
        final int kind;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), f = new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(c, 1.8f));
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            f.setColor(color); f.setStyle(Paint.Style.FILL);
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = p.getStrokeWidth();
            switch (kind) {
                case TV:
                    c.drawRoundRect(new RectF(s, h * 0.18f, w - s, h * 0.78f), w * 0.12f, w * 0.12f, p);
                    c.drawLine(w * 0.32f, h - s, w * 0.68f, h - s, p);
                    break;
                case GEAR: {
                    float cx = w / 2, cy = h / 2, r = w * 0.22f;
                    c.drawCircle(cx, cy, r, p);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI / 4 * i;
                        c.drawLine(cx + (float) Math.cos(a) * r * 1.45f, cy + (float) Math.sin(a) * r * 1.45f,
                                cx + (float) Math.cos(a) * r * 2.0f, cy + (float) Math.sin(a) * r * 2.0f, p);
                    }
                    break;
                }
                case SUN: {
                    float cx = w / 2, cy = h / 2;
                    c.drawCircle(cx, cy, w * 0.2f, f);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI / 4 * i;
                        c.drawLine(cx + (float) Math.cos(a) * w * 0.32f, cy + (float) Math.sin(a) * w * 0.32f,
                                cx + (float) Math.cos(a) * w * 0.45f, cy + (float) Math.sin(a) * w * 0.45f, p);
                    }
                    break;
                }
                case MOON: {
                    android.graphics.Path m = new android.graphics.Path();
                    m.addCircle(w * 0.5f, h * 0.5f, w * 0.4f, android.graphics.Path.Direction.CW);
                    android.graphics.Path cut = new android.graphics.Path();
                    cut.addCircle(w * 0.7f, h * 0.32f, w * 0.32f, android.graphics.Path.Direction.CW);
                    m.op(cut, android.graphics.Path.Op.DIFFERENCE);
                    c.drawPath(m, f);
                    break;
                }
                case HALF:
                    c.drawCircle(w / 2, h / 2, w * 0.4f, p);
                    c.drawArc(new RectF(w * 0.1f, h * 0.1f, w * 0.9f, h * 0.9f), 90, 180, true, f);
                    break;
                case PLAY: {
                    android.graphics.Path t = new android.graphics.Path();
                    t.moveTo(w * 0.3f, h * 0.18f); t.lineTo(w * 0.82f, h * 0.5f); t.lineTo(w * 0.3f, h * 0.82f); t.close();
                    c.drawPath(t, f);
                    break;
                }
                case MIRROR:
                    c.drawRoundRect(new RectF(s, h * 0.12f, w * 0.7f, h * 0.7f), w * 0.08f, w * 0.08f, p);
                    c.drawRoundRect(new RectF(w * 0.62f, h * 0.38f, w - s, h - s), w * 0.06f, w * 0.06f, p);
                    break;
                case DESKTOP:
                    c.drawRoundRect(new RectF(s, h * 0.12f, w - s, h * 0.72f), w * 0.08f, w * 0.08f, p);
                    c.drawLine(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.88f, p);
                    c.drawLine(w * 0.3f, h * 0.9f, w * 0.7f, h * 0.9f, p);
                    break;
                case REMOTE:
                    c.drawRoundRect(new RectF(w * 0.3f, h * 0.05f, w * 0.7f, h * 0.95f), w * 0.1f, w * 0.1f, p);
                    c.drawCircle(w / 2, h * 0.27f, w * 0.08f, f);
                    for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++)
                        c.drawCircle(w * (0.42f + 0.16f * j), h * (0.55f + 0.16f * i), w * 0.035f, f);
                    break;
            }
        }
    }
}
