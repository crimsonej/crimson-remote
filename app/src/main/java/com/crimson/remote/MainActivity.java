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
        store = new TvStore(this);
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

        refreshHeader();
    }


    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (prefs.getBoolean("bridge_on", false)) renderBridgeAddresses();   // addresses change with the network
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

    // Connection states shown on the connection card.
    static final int ST_IDLE = 0, ST_SEARCHING = 1, ST_CONNECTING = 2, ST_CONNECTED = 3, ST_OFFLINE = 4,
            ST_PAIRING = 5, ST_DISCONNECTED = 6, ST_NONE = 7, ST_CHOOSE = 8, ST_WAKING = 9;
    volatile int connState = ST_IDLE;
    volatile boolean userDisconnected;
    TvStore store;

    /** The non-Hisense TV currently controlled (Roku, Samsung, LG), or null. */
    volatile TvDriver driver;

    boolean isConnected() { return (mqtt != null && mqtt.isConnected()) || (driver != null && driver.isConnected()); }

    TvStore.Tv currentTv() { return store.current(); }

    /** Automatic connect (app opened). Does nothing if you pressed Disconnect. */
    void connect() {
        if (userDisconnected) { renderConnection(); return; }
        if (isConnected()) { setState(ST_CONNECTED, null); return; }
        final TvStore.Tv cur = currentTv();
        setState(cur == null ? ST_SEARCHING : ST_CONNECTING, null);
        net.execute(() -> {
            if (cur != null && open(cur)) return;
            setStateBg(ST_SEARCHING, null);
            discover();
            if (cur != null) {
                String[] match = findSaved(cur);
                if (match != null && openFound(match)) return;
                setStateBg(ST_OFFLINE, null);
                return;
            }
            List<String[]> list;
            synchronized (devices) { list = new ArrayList<>(devices); }
            if (list.isEmpty()) { setStateBg(ST_NONE, null); return; }
            if (list.size() == 1) { if (!openFound(list.get(0))) setStateBg(ST_OFFLINE, null); return; }
            setStateBg(ST_CHOOSE, null);
            MAIN.post(this::devicesSheet);
        });
    }

    /** Connect button: start again from scratch. */
    void reconnect() {
        userDisconnected = false;
        closeConnection();
        connect();
    }

    /** Disconnect button: stays disconnected until you connect again. */
    void disconnectNow() {
        userDisconnected = true;
        closeConnection();
        setState(ST_DISCONNECTED, null);
    }

    void closeConnection() {
        final Mqtt m = mqtt;
        mqtt = null;
        final TvDriver d = driver;
        driver = null;
        connectedIp = null;
        if (m != null) net.execute(m::close);
        if (d != null) net.execute(d::close);
    }

    /** A found TV ({ip, name, model, brand, mac}) that is the saved one. */
    String[] findSaved(TvStore.Tv t) {
        synchronized (devices) {
            for (String[] d : devices) {
                String mac = TvStore.normalMac(d[4]);
                if (!t.mac.isEmpty() && t.mac.equals(mac)) return d;
            }
            for (String[] d : devices) if (d[0].equals(t.ip) && d[3].equals(t.brand)) return d;
        }
        return null;
    }

    /** Saves a found TV and connects to it. */
    boolean openFound(String[] d) {
        TvStore.Tv t = new TvStore.Tv();
        t.ip = d[0]; t.name = d[1]; t.model = d[2]; t.brand = d[3]; t.mac = d[4];
        TvStore.Tv saved = store.save(t);
        store.setCurrent(saved.id);
        return open(saved);
    }

    boolean open(TvStore.Tv t) {
        setStateBg(ST_CONNECTING, null);
        return TvDriver.HISENSE.equals(t.brand) ? tryConnect(t.ip) : tryDriver(t);
    }

    /** Learns the TV's name, model and MAC after connecting and saves them. */
    void learn(String host) {
        new Thread(() -> {
            String[] info = TvDriver.identify(host);
            if (info == null) return;
            TvStore.Tv t = currentTv();
            if (t == null) t = new TvStore.Tv();
            t.ip = host; t.brand = info[0];
            if (info[1] != null) t.name = info[1];
            if (info[2] != null) t.model = info[2];
            if (info[3] != null) t.mac = info[3];
            TvStore.Tv saved = store.save(t);
            store.setCurrent(saved.id);
            MAIN.post(this::refreshHeader);
        }).start();
    }

    boolean tryDriver(TvStore.Tv tv) {
        if (!resumed) return false;
        final String host = tv.ip;
        TvDriver d = TvDriver.create(this, tv.brand, host, tv.id, new TvDriver.Listener() {
            @Override public void onConnected() { connectedIp = host; setStateBg(ST_CONNECTED, null); MAIN.post(MainActivity.this::refreshHeader); }
            @Override public void onDisconnected() {
                MAIN.post(() -> {
                    if (driver != null && driver.ip.equals(host)) { driver = null; connectedIp = null; if (!userDisconnected) setState(ST_OFFLINE, null); }
                });
            }
            @Override public void onPrompt(String m) { setStateBg(ST_PAIRING, m); MAIN.post(() -> { swap(nowTitle, "Check your TV"); swap(nowSub, m); }); }
            @Override public void onState(String t, String sub) { MAIN.post(() -> { swap(nowTitle, t); swap(nowSub, sub); }); }
            @Override public void onVolume(int v) { MAIN.post(() -> showVolume(v)); }
            @Override public void onApps(JSONArray apps) { MAIN.post(() -> renderApps(apps)); }
            @Override public void onSources(JSONArray src) { MAIN.post(() -> { sources = src; renderInputs(); }); }
            @Override public void onModel(String model) {
                TvStore.Tv t = currentTv();
                if (t != null) { t.model = model.trim(); store.save(t); }
                MAIN.post(MainActivity.this::refreshHeader);
            }
            @Override public void onMac(String mac) {
                TvStore.Tv t = currentTv();
                if (t != null && TvStore.normalMac(mac).length() == 17) { t.mac = mac; store.setCurrent(store.save(t).id); }
                MAIN.post(MainActivity.this::refreshHeader);
            }
        });
        if (d == null || !d.connect()) return false;
        final TvDriver old = driver;
        if (old != null) old.close();
        final Mqtt m = mqtt;
        mqtt = null;
        if (m != null) m.close();
        driver = d;
        connectedIp = host;
        if (connState != ST_PAIRING) setStateBg(ST_CONNECTED, null);
        MAIN.post(() -> { refreshHeader(); volBar.setEnabled(d.canSetVolume()); });
        return true;
    }

    boolean tryConnect(String host) {
        if (!resumed) return false;
        Mqtt m = new Mqtt(host, PORT, cid, "hisenseservice", "multimqttservice", this);
        try {
            m.connect();
            final TvDriver old = driver;
            driver = null;
            if (old != null) old.close();
            mqtt = m;
            connectedIp = host;
            MAIN.post(() -> { volBar.setEnabled(true); refreshHeader(); });
            m.subscribe("/remoteapp/mobile/#");
            refresh();
            learn(host);
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

    /** Finds every TV on this Wi-Fi and reads its brand, name and MAC. Runs off the main thread. */
    void discover() {
        scanning = true;
        MAIN.post(() -> { refreshHeader(); if (devicesList != null) renderDevices(); });
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
            String[] info = TvDriver.identify(h);   // {brand, name, model, mac}
            if (info == null) continue;
            String mac = TvStore.normalMac(info[3]);
            found.add(new String[]{h, info[1] != null ? info[1] : brandLabel(info[0]) + " TV", info[2], info[0], mac});
            // A saved TV that moved to a new address gets its address updated.
            TvStore.Tv saved = mac.isEmpty() ? null : store.byMac(mac);
            if (saved != null && !saved.ip.equals(h)) { saved.ip = h; store.save(saved); }
        }
        java.util.Collections.sort(found, (a, b) -> Integer.compare(lastOctet(a[0]), lastOctet(b[0])));
        synchronized (devices) { devices.clear(); devices.addAll(found); }
        scanning = false;
        MAIN.post(() -> { refreshHeader(); if (devicesList != null) renderDevices(); });
    }

    static int lastOctet(String ip) {
        try { return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1)); } catch (Exception e) { return 0; }
    }

    /** The current TV's name, or nothing yet. */
    String displayName() {
        TvStore.Tv t = currentTv();
        if (t == null) return "No TV yet";
        if (t.name != null && !t.name.isEmpty()) return t.name;
        return brandLabel(t.brand) + " TV";
    }

    void refreshHeader() {
        if (title != null) swap(title, displayName() + "  ▾");
        if (deviceBadge != null) {
            int n = devices.size();
            deviceBadge.setText(String.valueOf(n));
            deviceBadge.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
        }
        renderConnection();
    }

    /** Switches to a found TV from the list. */
    void selectFound(String[] d) {
        buzz(this, 12);
        userDisconnected = false;
        closeConnection();
        swap(nowTitle, "Connecting…");
        swap(nowSub, d[1]);
        setState(ST_CONNECTING, null);
        net.execute(() -> { if (!openFound(d)) setStateBg(ST_OFFLINE, null); });
    }

    /** Switches to a saved TV that may not have been found right now. */
    void selectSaved(TvStore.Tv t) {
        buzz(this, 12);
        store.setCurrent(t.id);
        reconnect();
    }

    String localPrefix() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                if (!n.startsWith("wlan") && !n.startsWith("eth")) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    String s = a.getHostAddress();
                    if (a instanceof java.net.Inet4Address) return s.substring(0, s.lastIndexOf('.') + 1);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override public void onConnected() { setStateBg(ST_CONNECTED, null); }

    @Override public void onDisconnected(String why) {
        mqtt = null;
        connectedIp = null;
        if (!userDisconnected) setStateBg(ST_OFFLINE, null);
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
                    setState(ST_CONNECTED, null);
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
                renderInputs();
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
        setState(ST_PAIRING, "Type the 4-digit code shown on the TV");
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
                    disconnectNow();
                    setState(ST_DISCONNECTED, "Not paired. Tap Connect to try again.");
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
        toast(connState == ST_CONNECTING || connState == ST_SEARCHING ? "Still connecting…" : "Not connected. Use the buttons at the top to connect.");
    }

    void power() {
        buzz(this, 25);
        if (driver != null && driver.isConnected()) { driver.key("KEY_POWER"); return; }
        if (mqtt != null) { key("KEY_POWER"); return; }
        wake();
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
        devicesSub.setText(scanning ? "Searching this Wi-Fi…" : devices.size() + " found on this Wi-Fi right now");
        devicesList.removeAllViews();
        List<String[]> found;
        synchronized (devices) { found = new ArrayList<>(devices); }
        List<TvStore.Tv> saved = store.all();
        TvStore.Tv cur = currentTv();

        // Saved TVs (remembered even when off), each marked found / not found.
        if (!saved.isEmpty()) {
            devicesList.addView(sectionTitle("Your TVs"));
            for (final TvStore.Tv t : saved) {
                String[] here = null;
                for (String[] d : found) {
                    if ((!t.mac.isEmpty() && t.mac.equals(d[4])) || (t.mac.isEmpty() && d[0].equals(t.ip))) here = d;
                }
                boolean isCurrent = cur != null && cur.id.equals(t.id);
                boolean connected = isCurrent && connState == ST_CONNECTED;
                String state = connected ? "Connected" : here != null ? "On · " + here[0] : scanning ? "Checking…" : "Not found";
                String sub = brandLabel(t.brand) + (t.model != null && !t.model.isEmpty() ? " · " + t.model : "")
                        + (t.canWake() ? "" : " · can't be turned on yet");
                final String[] foundNow = here;
                deviceRow(t.name != null && !t.name.isEmpty() ? t.name : brandLabel(t.brand) + " TV", sub, state, connected,
                        () -> { if (foundNow != null) selectFound(foundNow); else selectSaved(t); renderDevices(); },
                        () -> confirmForget(t));
            }
        }

        // TVs found now that aren't saved yet.
        List<String[]> fresh = new ArrayList<>();
        for (String[] d : found) {
            boolean known = false;
            for (TvStore.Tv t : saved) if ((!t.mac.isEmpty() && t.mac.equals(d[4])) || (t.mac.isEmpty() && t.ip.equals(d[0]))) known = true;
            if (!known) fresh.add(d);
        }
        if (!fresh.isEmpty()) {
            devicesList.addView(sectionTitle(saved.isEmpty() ? "Found on this Wi-Fi" : "Other TVs found"));
            for (final String[] d : fresh) {
                deviceRow(d[1], brandLabel(d[3]) + (d[2] != null ? " · " + d[2] : "") + " · " + d[0], "Connect", false,
                        () -> { selectFound(d); renderDevices(); }, null);
            }
        }
        if (saved.isEmpty() && fresh.isEmpty()) {
            TextView empty = text(this, scanning ? "Looking for TVs…"
                    : "No TVs found. Turn the TV on, keep this phone on the same Wi-Fi, then tap ↻.", 14, SUB, 400);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(this, 10), dp(this, 30), dp(this, 10), dp(this, 30));
            devicesList.addView(empty);
        }
    }

    void deviceRow(String name, String sub, String state, boolean connected, Runnable onTap, Runnable onForget) {
        LinearLayout r = row();
        r.setPadding(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
        r.setBackground(ripple(shape(SURFACE, dp(this, 18), STROKE, this)));
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(connected ? gradient(CRIMSON, CRIMSON_DARK, dp(this, 12)) : shape(SURFACE2, dp(this, 12), 0, this));
        ic.addView(new Icon(this, Icon.TV, connected ? Color.WHITE : SUB), new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
        r.addView(ic, new LinearLayout.LayoutParams(dp(this, 46), dp(this, 46)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(this, 14), 0, dp(this, 8), 0);
        t.addView(text(this, name, 16, TEXT, 500));
        t.addView(text(this, sub, 12, SUB, 400), margins(-2, -2, 0, 4));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        int c = connected ? GREEN : state.startsWith("On") || state.equals("Connect") ? CRIMSON : FAINT;
        TextView st = text(this, state, 12, c, 500);
        st.setPadding(dp(this, 10), dp(this, 6), dp(this, 10), dp(this, 6));
        st.setBackground(shape((c & 0x00FFFFFF) | 0x22000000, dp(this, 14), 0, this));
        r.addView(st);
        if (onForget != null) {
            TextView x = text(this, "✕", 14, FAINT, 500);
            x.setGravity(Gravity.CENTER);
            x.setContentDescription("Forget this TV");
            press(x, onForget, false);
            LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34));
            xl.leftMargin = dp(this, 6);
            r.addView(x, xl);
        }
        press(r, onTap, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(this, 10);
        devicesList.addView(r, lp);
    }

    void confirmForget(TvStore.Tv t) {
        new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle("Forget " + (t.name != null && !t.name.isEmpty() ? t.name : "this TV") + "?")
                .setMessage("Crimson will remove its saved address, MAC and pairing. You can add it again by connecting.")
                .setPositiveButton("Forget", (d, w) -> {
                    TvStore.Tv cur = currentTv();
                    if (cur != null && cur.id.equals(t.id)) disconnectNow();
                    store.forget(t.id);
                    renderDevices();
                    refreshHeader();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
        TvStore.Tv curTv = currentTv();
        if (curTv != null) {
            TextView facts = text(this, "Saved: " + brandLabel(curTv.brand) + " · " + curTv.ip
                    + (curTv.canWake() ? " · MAC " + curTv.mac : " · MAC not known yet"), 12, SUB, 400);
            facts.setPadding(dp(this, 14), 0, dp(this, 14), dp(this, 10));
            tvg.addView(facts);
        }
        TextView addHint = text(this, "Add a TV by address (only if it isn't found automatically)", 12, FAINT, 400);
        addHint.setPadding(dp(this, 14), dp(this, 4), dp(this, 14), 0);
        tvg.addView(addHint);
        final EditText ipBox = field("e.g. 192.168.1.20", "", InputType.TYPE_CLASS_PHONE);
        tvg.addView(ipBox);
        TextView save = text(this, "Add and connect", 14, Color.WHITE, 500);
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
        TextView stl = text(this, "Style", 13, SUB, 500);
        stl.setPadding(dp(this, 14), dp(this, 6), 0, dp(this, 6));
        ag.addView(stl);
        int curStyle = prefs.getInt("style", 0);
        for (int i = 0; i < 3; i++) {
            final int st = i;
            LinearLayout sr = row();
            sr.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
            sr.setBackground(ripple(shape(0, dp(this, 14), 0, this)));
            View sw = new View(this);
            int[] previews = {blend(0xFF1A0B11, CRIMSON, 0.45f), 0xFF17171E, 0xFF000000};
            sw.setBackground(shape(previews[i], dp(this, 10), STROKE, this));
            sr.addView(sw, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding(dp(this, 12), 0, 0, 0);
            tx.addView(text(this, STYLE_NAMES[i], 15, TEXT, 500));
            tx.addView(text(this, STYLE_BLURBS[i], 12, SUB, 400), margins(-2, -2, 0, 2));
            sr.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            sr.addView(text(this, curStyle == i ? "●" : "○", 18, curStyle == i ? CRIMSON : FAINT, 500));
            press(sr, () -> { prefs.edit().putInt("style", st).apply(); recreate(); }, false);
            ag.addView(sr);
        }
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
            final String host = ipBox.getText().toString().trim();
            if (!host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) { toast("Type an address like 192.168.1.20"); return; }
            d.dismiss();
            setState(ST_CONNECTING, null);
            net.execute(() -> {
                String[] info = TvDriver.identify(host);
                if (info == null) { setStateBg(ST_OFFLINE, null); MAIN.post(() -> toast("No supported TV answered at " + host)); return; }
                userDisconnected = false;
                closeConnection();
                if (!openFound(new String[]{host, info[1] != null ? info[1] : brandLabel(info[0]) + " TV", info[2], info[0], TvStore.normalMac(info[3])}))
                    setStateBg(ST_OFFLINE, null);
            });
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


    // ================================================================ layout

    // Screens and the bottom tab bar.
    FrameLayout screensFrame;
    View[] screens = new View[3];
    TextView[] navLabels = new TextView[3];
    Icon[] navIcons = new Icon[3];
    int screen;
    TextView screenLabel;
    View headerDot;
    LinearLayout banner;
    FrameLayout padHolder;
    boolean touchpadMode;

    View build() {
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.addView(new GlowBackground(this), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(header(), new LinearLayout.LayoutParams(-1, -2));
        screensFrame = new FrameLayout(this);
        col.addView(screensFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        col.addView(navBar(), new LinearLayout.LayoutParams(-1, -2));
        rootFrame.addView(col, new FrameLayout.LayoutParams(-1, -1));

        screens[0] = scrolled(remoteScreen());
        screens[1] = scrolled(appsScreen());
        screens[2] = scrolled(castScreen());
        for (View v : screens) screensFrame.addView(v, new FrameLayout.LayoutParams(-1, -1));
        showScreen(0, false);

        // Splash with the Crimson name, then everything rises in gently.
        col.setAlpha(0f);
        col.setTranslationY(dp(this, 18));
        final View splash = splash();
        rootFrame.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        MAIN.postDelayed(() -> {
            splash.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(420)
                    .withEndAction(() -> rootFrame.removeView(splash)).start();
            col.animate().alpha(1f).translationY(0).setStartDelay(120).setDuration(480)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }, 1300);
        return rootFrame;
    }

    ScrollView scrolled(View content) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.addView(content);
        return sv;
    }

    LinearLayout screenColumn() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(this, 20), dp(this, 6), dp(this, 20), dp(this, 28));
        c.setLayoutTransition(new LayoutTransition());
        return c;
    }

    View header() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(this, 20), dp(this, 14), dp(this, 20), dp(this, 10));
        wrap.setLayoutTransition(new LayoutTransition());

        LinearLayout h = row();
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        screenLabel = text(this, "CRIMSON · REMOTE", 11, CRIMSON, 700);
        screenLabel.setLetterSpacing(0.24f);
        left.addView(screenLabel);
        LinearLayout nameRow = row();
        headerDot = new View(this);
        headerDot.setBackground(shape(FAINT, dp(this, 5), 0, this));
        nameRow.addView(headerDot, new LinearLayout.LayoutParams(dp(this, 10), dp(this, 10)));
        title = text(this, "No TV yet  ▾", 24, TEXT, 700);
        title.setPadding(dp(this, 8), 0, 0, 0);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameRow.addView(title);
        press(nameRow, this::devicesSheet, false);
        nameRow.setContentDescription("Choose TV");
        left.addView(nameRow, margins(-2, -2, 0, 6));
        h.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        FrameLayout gear = new FrameLayout(this);
        gear.setBackground(ripple(shape(SURFACE2, dp(this, 22), STROKE, this)));
        gear.addView(new Icon(this, Icon.GEAR, SUB), new FrameLayout.LayoutParams(dp(this, 20), dp(this, 20), Gravity.CENTER));
        gear.setContentDescription("Settings");
        press(gear, this::settings, false);
        h.addView(gear, new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        wrap.addView(h);

        // Banner: only shown when the connection needs attention.
        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        banner.setBackground(shape(SURFACE, dp(this, 20), STROKE, this));
        statusDot = new View(this);
        statusDot.setBackground(shape(AMBER, dp(this, 5), 0, this));
        banner.addView(statusDot, new LinearLayout.LayoutParams(dp(this, 10), dp(this, 10)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(this, 10), 0, dp(this, 8), 0);
        statusText = text(this, "Starting", 14, TEXT, 500);
        texts.addView(statusText);
        statusDetail = text(this, "", 12, SUB, 400);
        texts.addView(statusDetail, margins(-2, -2, 0, 2));
        banner.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        connButtons = row();
        banner.addView(connButtons, new LinearLayout.LayoutParams(-2, -2));
        wrap.addView(banner, margins(-1, -2, 0, 12));
        return wrap;
    }

    View navBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(this, 8), dp(this, 6), dp(this, 8), dp(this, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(style == 0 ? (dark ? 0xE6101015 : 0xEFFFFFFF) : SURFACE);
        bg.setStroke(dp(this, 1), STROKE);
        bar.setBackground(bg);
        String[] names = {"Remote", "Apps", "Cast"};
        int[] icons = {Icon.REMOTE, Icon.GRID, Icon.CASTTV};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(this, 6), 0, dp(this, 4));
            item.setBackground(ripple(shape(0, dp(this, 16), 0, this)));
            navIcons[i] = new Icon(this, icons[i], SUB);
            item.addView(navIcons[i], new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24)));
            navLabels[i] = text(this, names[i], 11, SUB, 500);
            item.addView(navLabels[i], margins(-2, -2, 0, 4));
            item.setOnClickListener(v -> { buzz(this, 8); showScreen(idx, true); });
            bar.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return bar;
    }

    void showScreen(int idx, boolean animate) {
        int from = screen;
        screen = idx;
        String[] labels = {"REMOTE", "APPS", "CAST"};
        screenLabel.setText("CRIMSON · " + labels[idx]);
        for (int i = 0; i < 3; i++) {
            boolean on = i == idx;
            navLabels[i].setTextColor(on ? CRIMSON : SUB);
            navIcons[i].setColor(on ? CRIMSON : SUB);
            View v = screens[i];
            if (on) {
                v.setVisibility(View.VISIBLE);
                if (animate && from != idx) {
                    v.setAlpha(0f);
                    v.setTranslationX((idx > from ? 1 : -1) * dp(this, 24));
                    v.animate().alpha(1f).translationX(0).setDuration(240)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                }
            } else v.setVisibility(View.GONE);
        }
        if (idx == 2) startCastPolling();
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

    // ---------------------------------------------------------------- Remote screen

    View remoteScreen() {
        LinearLayout c = screenColumn();
        c.addView(nowCard());

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(this, 16), dp(this, 20), dp(this, 16), dp(this, 20));
        controls.setBackground(shape(SURFACE, dp(this, 30), STROKE, this));
        controls.setLayoutTransition(new LayoutTransition());
        pages = new FrameLayout(this);   // dimmed while not connected

        LinearLayout top = row();
        View pw = round(new PowerIcon(this), 0, dp(this, 58), true);
        press(pw, this::power, false);
        pw.setContentDescription("Power");
        top.addView(pw);
        top.addView(flex());
        top.addView(labeledKey(Icon.INPUT, "Input", this::chooseInput));
        top.addView(flex());
        top.addView(labeledKey(Icon.MUTE, "Mute", () -> key("KEY_MUTE")));
        top.addView(flex());
        top.addView(labeledKey(Icon.KEYBOARD, "Keyboard", this::keyboardSheet));
        controls.addView(top);

        // D-pad or touchpad, in the same spot.
        touchpadMode = prefs.getBoolean("padMode", false);
        LinearLayout sw = row();
        sw.setPadding(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
        sw.setBackground(shape(SURFACE2, dp(this, 20), 0, this));
        final TextView dpadBtn = text(this, "D-pad", 13, TEXT, 500);
        final TextView padBtn = text(this, "Touchpad", 13, TEXT, 500);
        for (TextView t : new TextView[]{dpadBtn, padBtn}) {
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(this, 18), 0, dp(this, 18), 0);
            sw.addView(t, new LinearLayout.LayoutParams(-2, dp(this, 34)));
        }
        LinearLayout.LayoutParams swl = new LinearLayout.LayoutParams(-2, -2);
        swl.gravity = Gravity.CENTER_HORIZONTAL;
        swl.topMargin = dp(this, 22);
        controls.addView(sw, swl);

        padHolder = new FrameLayout(this);
        controls.addView(padHolder, margins(-1, -2, 0, 20));
        final Runnable paintMode = () -> {
            dpadBtn.setBackground(touchpadMode ? null : gradient(CRIMSON, CRIMSON_DARK, dp(this, 17)));
            dpadBtn.setTextColor(touchpadMode ? SUB : Color.WHITE);
            padBtn.setBackground(touchpadMode ? gradient(CRIMSON, CRIMSON_DARK, dp(this, 17)) : null);
            padBtn.setTextColor(touchpadMode ? Color.WHITE : SUB);
            padHolder.removeAllViews();
            View v = touchpadMode ? touchpadArea() : dpadArea();
            v.setAlpha(0f);
            padHolder.addView(v, new FrameLayout.LayoutParams(-1, -2));
            v.animate().alpha(1f).setDuration(220).start();
        };
        dpadBtn.setOnClickListener(v -> { buzz(this, 8); touchpadMode = false; prefs.edit().putBoolean("padMode", false).apply(); paintMode.run(); });
        padBtn.setOnClickListener(v -> { buzz(this, 8); touchpadMode = true; prefs.edit().putBoolean("padMode", true).apply(); paintMode.run(); });
        paintMode.run();

        LinearLayout nav = row();
        Object[][] navKeys = {{Icon.BACK, "Back", "KEY_RETURNS"}, {Icon.HOME, "Home", "KEY_HOME"}, {Icon.MENU, "Menu", "KEY_MENU"}, {Icon.DOTS, "More", null}};
        for (Object[] k : navKeys) {
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setBackground(ripple(shape(SURFACE2, dp(this, 18), 0, this)));
            b.addView(new Icon(this, (int) k[0], TEXT), new LinearLayout.LayoutParams(dp(this, 20), dp(this, 20)));
            b.addView(text(this, (String) k[1], 11, SUB, 500), margins(-2, -2, 0, 5));
            final String code = (String) k[2];
            press(b, () -> { if (code != null) key(code); else moreSheet(); }, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(this, 60), 1);
            lp.leftMargin = lp.rightMargin = dp(this, 4);
            nav.addView(b, lp);
        }
        controls.addView(nav, margins(-1, -2, 0, 22));

        LinearLayout media = new LinearLayout(this);
        media.setOrientation(LinearLayout.HORIZONTAL);
        media.setPadding(dp(this, 6), dp(this, 6), dp(this, 6), dp(this, 6));
        media.setBackground(shape(SURFACE2, dp(this, 28), 0, this));
        media.addView(mediaBtn("◀◀", "KEY_BACK"), weight());
        final TextView pp = text(this, "❚❚", 16, TEXT, 500);
        pp.setGravity(Gravity.CENTER);
        pp.setBackground(ripple(shape(0, dp(this, 24), 0, this)));
        press(pp, () -> {
            key(playing ? "KEY_PAUSE" : "KEY_PLAY");
            playing = !playing;
            swap(pp, playing ? "❚❚" : "▶");
        }, false);
        media.addView(pp, weight());
        media.addView(mediaBtn("▶▶", "KEY_FORWARDS"), weight());
        controls.addView(media, margins(-1, dp(this, 54), 0, 14));

        c.addView(controls, margins(-1, -2, 0, 18));
        pages = null;
        controlsCard = controls;
        return c;
    }

    View controlsCard;

    View labeledKey(int icon, String label, Runnable r) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout k = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(SURFACE2);
        g.setStroke(dp(this, 1), STROKE);
        k.setBackground(ripple(g));
        k.addView(new Icon(this, icon, TEXT), new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
        col.addView(k, new LinearLayout.LayoutParams(dp(this, 52), dp(this, 52)));
        col.addView(text(this, label, 11, SUB, 500), margins(-2, -2, 0, 6));
        press(col, r, false);
        col.setContentDescription(label);
        return col;
    }

    View dpadArea() {
        LinearLayout mid = row();
        mid.addView(rocker("VOL", "KEY_VOLUMEUP", "KEY_VOLUMEDOWN"));
        mid.addView(flex());
        mid.addView(dpad());
        mid.addView(flex());
        mid.addView(rocker("CH", "KEY_CHANNELUP", "KEY_CHANNELDOWN"));
        return mid;
    }

    View touchpadArea() {
        FrameLayout f = new FrameLayout(this);
        TouchpadView pad = new TouchpadView(this);
        pad.setListener(new TouchpadView.Listener() {
            @Override public void onStep(String k) { key(k); }
            @Override public void onTap() { key("KEY_OK"); }
        });
        f.addView(pad, new FrameLayout.LayoutParams(-1, dp(this, 250)));
        TextView hint = text(this, "Swipe to move · tap to select", 12, FAINT, 400);
        f.addView(hint, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        ((FrameLayout.LayoutParams) hint.getLayoutParams()).bottomMargin = dp(this, 12);
        final TextView pointer = text(this, "⌖", 18, SUB, 500);
        pointer.setGravity(Gravity.CENTER);
        pointer.setBackground(ripple(shape(SURFACE, dp(this, 17), STROKE, this)));
        pointer.setContentDescription("Pointer mode");
        press(pointer, () -> {
            key("KEY_LEFTMOUSEKEYS");
            pointerMode = !pointerMode;
            pointer.setBackground(ripple(pointerMode ? gradient(CRIMSON, CRIMSON_DARK, dp(this, 17)) : shape(SURFACE, dp(this, 17), STROKE, this)));
            pointer.setTextColor(pointerMode ? Color.WHITE : SUB);
            toast(pointerMode ? "Pointer on" : "Pointer off");
        }, false);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(this, 34), dp(this, 34), Gravity.TOP | Gravity.END);
        plp.setMargins(0, dp(this, 10), dp(this, 10), 0);
        f.addView(pointer, plp);
        return f;
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

    // ---------------------------------------------------------------- Cast screen

    static final int PICK_MEDIA = 41;
    Caster caster;
    LinearLayout nowCast;
    TextView castTitle, castState, castTimes;
    SeekBar castSeek;
    TextView castPlayBtn;
    boolean castSeeking;
    String castKind = "";

    View castScreen() {
        LinearLayout c = screenColumn();

        // Now casting: only shown while something is on the TV.
        nowCast = new LinearLayout(this);
        nowCast.setOrientation(LinearLayout.VERTICAL);
        nowCast.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
        nowCast.setBackground(shape(SURFACE, dp(this, 24), STROKE, this));
        TextView label = text(this, "NOW CASTING", 11, FAINT, 700);
        label.setLetterSpacing(0.16f);
        nowCast.addView(label);
        castTitle = text(this, "", 16, TEXT, 500);
        castTitle.setSingleLine(true);
        castTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        nowCast.addView(castTitle, margins(-1, -2, 0, 6));
        castState = text(this, "", 12, SUB, 400);
        nowCast.addView(castState, margins(-1, -2, 0, 2));
        castSeek = new SeekBar(this);
        castSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(CRIMSON));
        castSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        castSeek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(TRACK));
        castSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {}
            public void onStartTrackingTouch(SeekBar s) { castSeeking = true; }
            public void onStopTrackingTouch(SeekBar s) {
                castSeeking = false;
                final int to = s.getProgress();
                new Thread(() -> { try { caster.seek(to); } catch (Exception ignored) {} }).start();
            }
        });
        nowCast.addView(castSeek, margins(-1, -2, 0, 10));
        castTimes = text(this, "", 11, SUB, 400);
        nowCast.addView(castTimes);
        LinearLayout cb = row();
        castPlayBtn = text(this, "Pause", 14, Color.WHITE, 500);
        castPlayBtn.setGravity(Gravity.CENTER);
        castPlayBtn.setBackground(ripple(gradient(CRIMSON, CRIMSON_DARK, dp(this, 23))));
        press(castPlayBtn, () -> castControl("toggle"), false);
        cb.addView(castPlayBtn, chipLp(1));
        TextView stop = chip("Stop casting");
        press(stop, () -> castControl("stop"), false);
        cb.addView(stop, chipLp(1));
        nowCast.addView(cb, margins(-1, -2, 0, 12));
        nowCast.setVisibility(View.GONE);
        c.addView(nowCast, margins(-1, -2, 0, 0));

        // Pick something to cast.
        LinearLayout pick = row();
        pick.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
        pick.setBackground(ripple(shape(SURFACE, dp(this, 24), STROKE, this)));
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(gradient(CRIMSON, CRIMSON_DARK, dp(this, 18)));
        ic.addView(new Icon(this, Icon.PLAY, Color.WHITE), new FrameLayout.LayoutParams(dp(this, 24), dp(this, 24), Gravity.CENTER));
        pick.addView(ic, new LinearLayout.LayoutParams(dp(this, 58), dp(this, 58)));
        LinearLayout pt = new LinearLayout(this);
        pt.setOrientation(LinearLayout.VERTICAL);
        pt.setPadding(dp(this, 16), 0, 0, 0);
        pt.addView(text(this, "Photos, videos & music", 17, TEXT, 700));
        pt.addView(text(this, "Pick something to show on the TV", 13, SUB, 400), margins(-2, -2, 0, 4));
        pick.addView(pt, new LinearLayout.LayoutParams(0, -2, 1));
        pick.addView(text(this, "›", 22, FAINT, 400));
        press(pick, () -> {
            if (connState != ST_CONNECTED) { toast("Connect to a TV first"); return; }
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*", "audio/*"});
            startActivityForResult(i, PICK_MEDIA);
        }, false);
        c.addView(pick, margins(-1, -2, 0, 18));

        c.addView(sectionTitle("More ways"));
        LinearLayout ways = group();
        ways.addView(wayRow(Icon.MIRROR, "Mirror this phone", "Uses the TV's Screen Share (Miracast)", this::openScreenCast));
        ways.addView(wayRow(Icon.DESKTOP, "Crimson Desktop", "A desktop on the TV while mirroring", this::openDesktop));
        c.addView(ways);

        c.addView(sectionTitle("For an iPhone browser"));
        c.addView(bridgeCard());
        return c;
    }

    View wayRow(int icon, String title, String sub, Runnable r) {
        LinearLayout row = row();
        row.setPadding(dp(this, 14), dp(this, 14), dp(this, 14), dp(this, 14));
        row.setBackground(ripple(shape(0, dp(this, 18), 0, this)));
        FrameLayout ic = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(CRIMSON_SOFT);
        ic.setBackground(g);
        ic.addView(new Icon(this, icon, CRIMSON), new FrameLayout.LayoutParams(dp(this, 20), dp(this, 20), Gravity.CENTER));
        row.addView(ic, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(this, 14), 0, 0, 0);
        t.addView(text(this, title, 15, TEXT, 500));
        t.addView(text(this, sub, 12, SUB, 400), margins(-2, -2, 0, 2));
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text(this, "›", 20, FAINT, 400));
        press(row, r, false);
        return row;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_MEDIA || res != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        final String tv = connectedIp != null ? connectedIp : currentTv() != null ? currentTv().ip : "";
        if (tv.isEmpty()) { toast("Connect to a TV first"); return; }
        if (caster == null) caster = new Caster(this);
        nowCast.setVisibility(View.VISIBLE);
        castTitle.setText(caster.displayName(uri));
        castState.setText("Sending to the TV…");
        castSeek.setVisibility(View.GONE);
        castTimes.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                caster.cast(tv, uri);
                castKind = caster.kind();
                MAIN.post(() -> { castState.setText(castKind.equals("photo") ? "Showing on the TV" : "Playing on the TV"); startCastPolling(); });
            } catch (Exception e) {
                MAIN.post(() -> castState.setText(String.valueOf(e.getMessage())));
            }
        }).start();
    }

    boolean castPaused;

    void castControl(String what) {
        if (caster == null) return;
        buzz(this, 10);
        new Thread(() -> {
            try {
                if (what.equals("stop")) {
                    caster.stop();
                    MAIN.post(() -> nowCast.setVisibility(View.GONE));
                } else if (castPaused) { caster.play(); castPaused = false; }
                else { caster.pause(); castPaused = true; }
                MAIN.post(() -> castPlayBtn.setText(castPaused ? "Play" : "Pause"));
            } catch (Exception e) {
                MAIN.post(() -> toast(String.valueOf(e.getMessage())));
            }
        }).start();
    }

    final Runnable castPoll = new Runnable() {
        @Override public void run() {
            if (caster == null || nowCast == null || nowCast.getVisibility() != View.VISIBLE || screen != 2) return;
            new Thread(() -> {
                String state = caster.transportState();
                long[] pos = castKind.equals("photo") ? null : caster.position();
                MAIN.post(() -> {
                    if (state != null) {
                        castPaused = state.equals("PAUSED_PLAYBACK");
                        castPlayBtn.setText(castPaused ? "Play" : "Pause");
                        castPlayBtn.setVisibility(castKind.equals("photo") ? View.GONE : View.VISIBLE);
                        if (state.equals("STOPPED") && pos != null && pos[1] > 0 && pos[0] >= pos[1] - 2) castState.setText("Finished");
                        else castState.setText(state.equals("PLAYING") ? (castKind.equals("photo") ? "Showing on the TV" : "Playing on the TV")
                                : castPaused ? "Paused" : state.equals("TRANSITIONING") ? "Loading on the TV…" : castState.getText().toString());
                    }
                    if (pos != null && pos[1] > 0) {
                        castSeek.setVisibility(View.VISIBLE);
                        castTimes.setVisibility(View.VISIBLE);
                        castSeek.setMax((int) pos[1]);
                        if (!castSeeking) castSeek.setProgress((int) pos[0]);
                        castTimes.setText(clock(pos[0]) + " / " + clock(pos[1]));
                    }
                });
            }).start();
            MAIN.postDelayed(this, 1000);
        }
    };

    void startCastPolling() {
        MAIN.removeCallbacks(castPoll);
        MAIN.post(castPoll);
    }

    static String clock(long s) {
        return s >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                : String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
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

    // ---------------------------------------------------------------- Keyboard and More

    void keyboardSheet() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(text(this, "Type on the TV", 22, TEXT, 700));
        c.addView(text(this, "Select a search box on the TV first.", 13, SUB, 400), margins(-2, -2, 0, 6));
        LinearLayout r = row();
        typed = "";
        typeBox = new EditText(this);
        typeBox.setTextColor(TEXT);
        typeBox.setHintTextColor(FAINT);
        typeBox.setHint("Start typing…");
        typeBox.setTextSize(18);
        typeBox.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        typeBox.setBackground(shape(SURFACE, dp(this, 16), STROKE, this));
        typeBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        typeBox.setImeOptions(EditorInfo.IME_ACTION_SEND);
        typeBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c2) {}
            public void onTextChanged(CharSequence s, int a, int b, int c2) {}
            public void afterTextChanged(Editable e) { syncTyped(e.toString()); }
        });
        typeBox.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEND || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                input("Lit_ENTER");
                buzz(this, 15);
                typed = "";
                typeBox.setText("");
                return true;
            }
            return false;
        });
        r.addView(typeBox, new LinearLayout.LayoutParams(0, -2, 1));
        TextView bk = text(this, "⌫", 18, TEXT, 500);
        bk.setGravity(Gravity.CENTER);
        bk.setBackground(ripple(shape(SURFACE2, dp(this, 26), STROKE, this)));
        bk.setContentDescription("Backspace");
        press(bk, () -> input("Lit_BACKSPACE"), true);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(this, 52), dp(this, 52));
        bl.leftMargin = dp(this, 10);
        r.addView(bk, bl);
        c.addView(r, margins(-1, -2, 0, 14));
        android.app.Dialog d = sheet(c);
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        typeBox.requestFocus();
    }

    void moreSheet() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(text(this, "More buttons", 22, TEXT, 700));
        String[][] nums = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"Info", "0", "Subtitles"}};
        for (String[] r : nums) {
            LinearLayout rr = row();
            for (String n : r) {
                String k = n.equals("Info") ? "KEY_INFO" : n.equals("Subtitles") ? "KEY_SUBTITLE" : "KEY_" + n;
                TextView t = text(this, n, n.length() == 1 ? 22 : 13, TEXT, 500);
                t.setGravity(Gravity.CENTER);
                t.setBackground(ripple(shape(SURFACE, dp(this, 18), STROKE, this)));
                press(t, () -> key(k), false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(this, 56), 1);
                lp.setMargins(dp(this, 5), dp(this, 5), dp(this, 5), dp(this, 5));
                rr.addView(t, lp);
            }
            c.addView(rr, margins(-1, -2, 0, 4));
        }
        LinearLayout colours = row();
        int[] cs = {0xFFEF4444, 0xFF22C55E, 0xFFEAB308, 0xFF3B82F6};
        String[] ck = {"KEY_RED", "KEY_GREEN", "KEY_YELLOW", "KEY_BLUE"};
        String[] cn = {"Red", "Green", "Yellow", "Blue"};
        for (int i = 0; i < 4; i++) {
            View col = new View(this);
            col.setBackground(ripple(shape(cs[i], dp(this, 12), 0, this)));
            col.setContentDescription(cn[i]);
            final String k = ck[i];
            press(col, () -> key(k), false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(this, 30), 1);
            lp.setMargins(dp(this, 6), dp(this, 12), dp(this, 6), 0);
            colours.addView(col, lp);
        }
        c.addView(colours);
        TextView exit = text(this, "Exit", 14, TEXT, 500);
        exit.setGravity(Gravity.CENTER);
        exit.setBackground(ripple(shape(SURFACE, dp(this, 18), STROKE, this)));
        press(exit, () -> key("KEY_EXIT"), false);
        c.addView(exit, margins(-1, dp(this, 50), 0, 18));
        sheet(c);
    }

    /** Sends only what changed: backspaces for removed text, letters for new text. */
    void syncTyped(String now) {
        int common = 0;
        while (common < now.length() && common < typed.length() && now.charAt(common) == typed.charAt(common)) common++;
        for (int i = common; i < typed.length(); i++) input("Lit_BACKSPACE");
        for (int i = common; i < now.length(); i++) {
            char ch = now.charAt(i);
            input(ch == ' ' ? "Lit_SPACE" : "Lit_" + ch);
        }
        typed = now;
    }

    // ---------------------------------------------------------------- apps

    LinearLayout inputsList;
    TextView appsEmpty;

    View appsScreen() {
        LinearLayout c = screenColumn();
        appsEmpty = text(this, "Your TV's apps show here once it's connected.", 14, SUB, 400);
        appsEmpty.setGravity(Gravity.CENTER);
        appsEmpty.setPadding(dp(this, 20), dp(this, 40), dp(this, 20), dp(this, 40));
        appsEmpty.setBackground(shape(SURFACE, dp(this, 24), STROKE, this));
        c.addView(appsEmpty);
        appsRow = new LinearLayout(this);   // the grid
        appsRow.setOrientation(LinearLayout.VERTICAL);
        c.addView(appsRow, margins(-1, -2, 0, 4));
        inputsList = new LinearLayout(this);
        inputsList.setOrientation(LinearLayout.VERTICAL);
        c.addView(inputsList, margins(-1, -2, 0, 10));
        return c;
    }

    void renderApps(JSONArray list) {
        appsRow.removeAllViews();
        boolean empty = list == null || list.length() == 0;
        appsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) { appsEmpty.setText(connState == ST_CONNECTED ? "Loading your TV's apps…" : "Your TV's apps show here once it's connected."); return; }
        appsRow.addView(sectionTitle("On your TV"));
        int[] tints = {0xFFE11D48, 0xFFF97316, 0xFF8B5CF6, 0xFF06B6D4, 0xFF22C55E, 0xFFEAB308, 0xFF3B82F6, 0xFFEC4899};
        LinearLayout line = null;
        int shown = 0;
        for (int i = 0; i < list.length() && shown < 24; i++) {
            final JSONObject app = list.optJSONObject(i);
            if (app == null) continue;
            String name = app.optString("name").trim().replaceAll("\\s+\\d+(\\.\\d+)*$", "");
            if (name.isEmpty()) continue;
            if (shown % 4 == 0) { line = row(); line.setGravity(Gravity.TOP); appsRow.addView(line, margins(-1, -2, 0, 12)); }
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
            tile.addView(badgeBox, new LinearLayout.LayoutParams(dp(this, 64), dp(this, 64)));
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
            line.addView(tile, new LinearLayout.LayoutParams(0, -2, 1));
            tile.setAlpha(0f);
            tile.setScaleX(0.9f); tile.setScaleY(0.9f);
            tile.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(shown * 25L).setDuration(260).start();
            shown++;
        }
        while (line != null && shown % 4 != 0) { line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1)); shown++; }
    }

    void renderInputs() {
        if (inputsList == null) return;
        inputsList.removeAllViews();
        if (sources.length() == 0) return;
        inputsList.addView(sectionTitle("Inputs"));
        for (int i = 0; i < sources.length(); i++) {
            final JSONObject s = sources.optJSONObject(i);
            if (s == null) continue;
            String name = s.optString("displayname", s.optString("sourcename"));
            String raw = s.optString("sourcename");
            boolean sig = "1".equals(s.optString("is_signal"));
            LinearLayout r = row();
            r.setPadding(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
            r.setBackground(ripple(shape(SURFACE, dp(this, 18), STROKE, this)));
            FrameLayout ic = new FrameLayout(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(CRIMSON_SOFT);
            ic.setBackground(g);
            ic.addView(new Icon(this, Icon.INPUT, CRIMSON), new FrameLayout.LayoutParams(dp(this, 18), dp(this, 18), Gravity.CENTER));
            r.addView(ic, new LinearLayout.LayoutParams(dp(this, 38), dp(this, 38)));
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setPadding(dp(this, 14), 0, 0, 0);
            t.addView(text(this, name, 15, TEXT, 500));
            if (!name.equals(raw)) t.addView(text(this, raw, 12, SUB, 400), margins(-2, -2, 0, 2));
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(text(this, sig ? "Signal" : "No signal", 11, sig ? GREEN : FAINT, 500));
            press(r, () -> {
                try {
                    JSONObject o = new JSONObject().put("sourceid", s.optString("sourceid")).put("sourcename", s.optString("sourcename"));
                    if (driver != null) driver.source(s); else action("ui_service", "changesource", o.toString());
                    toast("Switching to " + s.optString("displayname"));
                } catch (Exception ignored) {}
            }, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(this, 10);
            inputsList.addView(r, lp);
        }
    }

    // ---------------------------------------------------------------- iPhone bridge

    LinearLayout bridgeList;

    /** One block per network the bridge can be reached on: label, link, QR code and buttons. */
    void renderBridgeAddresses() {
        if (bridgeList == null) return;
        bridgeList.removeAllViews();
        List<String[]> addrs = Bridge.addresses();
        if (addrs.isEmpty()) {
            bridgeList.addView(text(this, "Connect this phone to Wi-Fi or turn on its hotspot.", 13, SUB, 400), margins(-1, -2, 0, 12));
            return;
        }
        for (final String[] a : addrs) {
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setGravity(Gravity.CENTER_HORIZONTAL);
            block.setPadding(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
            block.setBackground(shape(SURFACE2, dp(this, 16), 0, this));
            TextView label = text(this, "If the iPhone is on: " + a[0], 12, SUB, 500);
            block.addView(label);
            TextView link = text(this, a[1], 16, TEXT, 700);
            link.setTextIsSelectable(true);
            block.addView(link, margins(-2, -2, 0, 6));
            android.widget.ImageView qr = new android.widget.ImageView(this);
            qr.setImageBitmap(qrBitmap(a[1]));
            qr.setBackgroundColor(Color.WHITE);
            int pad = dp(this, 8);
            qr.setPadding(pad, pad, pad, pad);
            block.addView(qr, margins(dp(this, 190), dp(this, 190), 0, 10));
            LinearLayout btns = row();
            TextView open = chip("Open here");
            press(open, () -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(a[1]))); }
                catch (Exception e) { toast("No browser found on this phone"); }
            }, false);
            btns.addView(open, chipLp(1));
            TextView copy = chip("Copy");
            press(copy, () -> {
                ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(android.content.ClipData.newPlainText("Crimson", a[1]));
                toast("Link copied");
            }, false);
            btns.addView(copy, chipLp(1));
            TextView share = chip("Share");
            press(share, () -> {
                Intent sh = new Intent(Intent.ACTION_SEND);
                sh.setType("text/plain");
                sh.putExtra(Intent.EXTRA_TEXT, "Crimson Remote: " + a[1]);
                startActivity(Intent.createChooser(sh, "Send the remote link"));
            }, false);
            btns.addView(share, chipLp(1));
            block.addView(btns, margins(-1, -2, 0, 10));
            bridgeList.addView(block, margins(-1, -2, 0, 10));
        }
    }

    /** A crisp QR code bitmap for a link. */
    android.graphics.Bitmap qrBitmap(String text) {
        io.nayuki.qrcodegen.QrCode q = io.nayuki.qrcodegen.QrCode.encodeText(text, io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM);
        int scale = 8, border = 2, size = (q.size + border * 2) * scale;
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565);
        bm.eraseColor(Color.WHITE);
        for (int y = 0; y < q.size; y++)
            for (int x = 0; x < q.size; x++)
                if (q.getModule(x, y))
                    for (int dy = 0; dy < scale; dy++)
                        for (int dx = 0; dx < scale; dx++)
                            bm.setPixel((x + border) * scale + dx, (y + border) * scale + dy, Color.BLACK);
        return bm;
    }

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
        TextView how = text(this, "On the iPhone, point the Camera at the code for the network the iPhone is on, "
                + "then tap the link. In Safari, tap Share, then Add to Home Screen.", 12, SUB, 400);
        how.setLineSpacing(0, 1.15f);
        on.addView(how, margins(-1, -2, 0, 12));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        on.addView(list);
        bridgeList = list;
        card.addView(on);

        final Runnable paint = () -> {
            boolean enabled = prefs.getBoolean("bridge_on", false);
            toggle.setBackground(enabled ? gradient(CRIMSON, CRIMSON_DARK, dp(this, 14)) : shape(SURFACE2, dp(this, 14), STROKE, this));
            knob.animate().translationX(enabled ? dp(this, 22) : 0).setDuration(240)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
            on.setVisibility(enabled ? View.VISIBLE : View.GONE);
            if (enabled) renderBridgeAddresses();
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

    TextView statusDetail;
    LinearLayout connButtons;
    String stateNote;

    void setState(int st, String note) {
        connState = st;
        stateNote = note;
        renderConnection();
    }

    void setStateBg(int st, String note) { MAIN.post(() -> setState(st, note)); }

    /** Draws the connection card for the current state, with only the buttons that make sense. */
    void renderConnection() {
        if (statusDot == null) return;
        TvStore.Tv tv = currentTv();
        String name = tv != null ? displayName() : null;
        int color; String head, detail; boolean busy = false;
        switch (connState) {
            case ST_CONNECTED:
                color = GREEN; head = "Connected";
                detail = (tv != null ? brandLabel(tv.brand) + " · " : "") + (connectedIp != null ? connectedIp : "");
                break;
            case ST_CONNECTING: color = AMBER; busy = true; head = "Connecting to " + (name != null ? name : "the TV"); detail = tv != null ? tv.ip : ""; break;
            case ST_SEARCHING: color = AMBER; busy = true; head = "Looking for TVs on this Wi-Fi"; detail = "This takes a few seconds"; break;
            case ST_WAKING: color = AMBER; busy = true; head = "Waking " + (name != null ? name : "the TV"); detail = "Sent the wake-up signal"; break;
            case ST_PAIRING: color = AMBER; busy = true; head = "Waiting for the TV"; detail = stateNote != null ? stateNote : "Approve Crimson on the TV"; break;
            case ST_DISCONNECTED: color = FAINT; head = "Disconnected"; detail = stateNote != null ? stateNote : "Tap Connect when you want to use the TV"; break;
            case ST_NONE: color = RED; head = "No TVs found"; detail = "Turn a TV on and keep this phone on the same Wi-Fi"; break;
            case ST_CHOOSE: color = AMBER; head = devices.size() + " TVs found"; detail = "Choose which one to use"; break;
            case ST_OFFLINE:
                color = RED; head = (name != null ? name : "The TV") + " isn't answering";
                detail = tv != null && tv.canWake() ? "It may be off. Tap Turn on." : "It may be off, or on another Wi-Fi";
                break;
            default: color = FAINT; head = "Starting"; detail = "";
        }
        statusDot.setBackground(shape(color, dp(this, 5), 0, this));
        if (headerDot != null) headerDot.setBackground(shape(color, dp(this, 5), 0, this));
        if (banner != null) banner.setVisibility(connState == ST_CONNECTED ? View.GONE : View.VISIBLE);
        if (pulse != null) { pulse.cancel(); pulse = null; statusDot.setAlpha(1f); }
        if (busy) pulse = Ui.pulse(statusDot);
        swap(statusText, head);
        statusDetail.setText(detail);
        statusDetail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);

        connButtons.removeAllViews();
        switch (connState) {
            case ST_CONNECTED:
                connButton("Disconnect", false, this::disconnectNow);
                connButton("Switch TV", false, this::devicesSheet);
                break;
            case ST_CONNECTING: case ST_SEARCHING: case ST_WAKING: case ST_PAIRING:
                connButton("Stop", false, this::disconnectNow);
                if (connState == ST_PAIRING) connButton("Try again", true, this::reconnect);
                break;
            case ST_OFFLINE:
                if (tv != null && tv.canWake()) connButton("Turn on", true, this::wake);
                connButton("Reconnect", tv == null || !tv.canWake(), this::reconnect);
                connButton("Switch TV", false, this::devicesSheet);
                break;
            case ST_CHOOSE:
                connButton("Choose TV", true, this::devicesSheet);
                connButton("Search again", false, this::reconnect);
                break;
            case ST_NONE:
                connButton("Search again", true, this::reconnect);
                connButton("Add by address", false, this::settings);
                break;
            default:
                connButton("Connect", true, this::reconnect);
                if (tv != null && tv.canWake()) connButton("Turn on", false, this::wake);
                connButton("Switch TV", false, this::devicesSheet);
        }
        boolean live = connState == ST_CONNECTED;
        if (controlsCard != null) controlsCard.animate().alpha(live ? 1f : 0.5f).setDuration(200).start();
    }

    void connButton(String label, boolean primary, Runnable r) {
        if (connButtons.getChildCount() >= (primary ? 2 : 1) && !primary) return;   // the banner keeps one main action
        TextView b = text(this, label, 13, primary ? Color.WHITE : TEXT, 500);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(this, 12), 0, dp(this, 12), 0);
        b.setBackground(ripple(primary ? gradient(CRIMSON, CRIMSON_DARK, dp(this, 18)) : shape(SURFACE2, dp(this, 18), STROKE, this)));
        press(b, r, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(this, 34));
        lp.leftMargin = dp(this, 6);
        connButtons.addView(b, lp);
    }

    /** Sends the wake-up signal to the saved TV, then reconnects. */
    void wake() {
        final TvStore.Tv tv = currentTv();
        if (tv == null || !tv.canWake()) { toast("Connect to this TV once while it's on, then Crimson can turn it on"); return; }
        buzz(this, 25);
        userDisconnected = false;
        setState(ST_WAKING, null);
        new Thread(() -> {
            for (int i = 0; i < 3; i++) { wakeOnLan(tv.mac); try { Thread.sleep(700); } catch (InterruptedException ignored) {} }
            for (int i = 0; i < 10 && !isConnected(); i++) {
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                if (open(tv)) return;
            }
            if (!isConnected()) setStateBg(ST_OFFLINE, null);
        }).start();
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
        static final int TV = 0, GEAR = 1, SUN = 2, MOON = 3, HALF = 4, REMOTE = 5, PLAY = 6, MIRROR = 7, DESKTOP = 8,
                GRID = 9, CASTTV = 10, INPUT = 11, MUTE = 12, KEYBOARD = 13, BACK = 14, HOME = 15, MENU = 16, DOTS = 17;
        final int kind;
        void setColor(int c) { p.setColor(c); f.setColor(c); invalidate(); }
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
                case GRID: {
                    float g = w * 0.1f, cell = (w - g * 3) / 2;
                    for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++)
                        c.drawRoundRect(new RectF(g + j * (cell + g), g + i * (cell + g), g + j * (cell + g) + cell, g + i * (cell + g) + cell), w * 0.06f, w * 0.06f, f);
                    break;
                }
                case CASTTV: {
                    c.drawRoundRect(new RectF(s, h * 0.15f, w - s, h * 0.75f), w * 0.1f, w * 0.1f, p);
                    android.graphics.Path t = new android.graphics.Path();
                    t.moveTo(w * 0.42f, h * 0.32f); t.lineTo(w * 0.64f, h * 0.45f); t.lineTo(w * 0.42f, h * 0.58f); t.close();
                    c.drawPath(t, f);
                    c.drawLine(w * 0.34f, h * 0.92f, w * 0.66f, h * 0.92f, p);
                    break;
                }
                case INPUT:
                    c.drawRoundRect(new RectF(s, h * 0.18f, w * 0.72f, h * 0.72f), w * 0.08f, w * 0.08f, p);
                    c.drawRoundRect(new RectF(w * 0.3f, h * 0.36f, w - s, h * 0.9f), w * 0.08f, w * 0.08f, p);
                    break;
                case MUTE: {
                    android.graphics.Path sp = new android.graphics.Path();
                    sp.moveTo(w * 0.08f, h * 0.36f); sp.lineTo(w * 0.30f, h * 0.36f); sp.lineTo(w * 0.55f, h * 0.12f);
                    sp.lineTo(w * 0.55f, h * 0.88f); sp.lineTo(w * 0.30f, h * 0.64f); sp.lineTo(w * 0.08f, h * 0.64f); sp.close();
                    c.drawPath(sp, f);
                    c.drawLine(w * 0.70f, h * 0.36f, w * 0.94f, h * 0.64f, p);
                    c.drawLine(w * 0.94f, h * 0.36f, w * 0.70f, h * 0.64f, p);
                    break;
                }
                case KEYBOARD: {
                    c.drawRoundRect(new RectF(s, h * 0.22f, w - s, h * 0.8f), w * 0.1f, w * 0.1f, p);
                    for (int i = 0; i < 4; i++) c.drawCircle(w * (0.22f + 0.185f * i), h * 0.42f, w * 0.035f, f);
                    c.drawLine(w * 0.3f, h * 0.62f, w * 0.7f, h * 0.62f, p);
                    break;
                }
                case BACK: {
                    android.graphics.Path a = new android.graphics.Path();
                    a.moveTo(w * 0.38f, h * 0.2f); a.lineTo(w * 0.15f, h * 0.42f); a.lineTo(w * 0.38f, h * 0.64f);
                    c.drawPath(a, p);
                    android.graphics.Path b2 = new android.graphics.Path();
                    b2.moveTo(w * 0.16f, h * 0.42f); b2.lineTo(w * 0.62f, h * 0.42f);
                    b2.quadTo(w * 0.88f, h * 0.42f, w * 0.88f, h * 0.64f); b2.quadTo(w * 0.88f, h * 0.86f, w * 0.62f, h * 0.86f);
                    b2.lineTo(w * 0.45f, h * 0.86f);
                    c.drawPath(b2, p);
                    break;
                }
                case HOME: {
                    android.graphics.Path hm = new android.graphics.Path();
                    hm.moveTo(w * 0.12f, h * 0.48f); hm.lineTo(w * 0.5f, h * 0.14f); hm.lineTo(w * 0.88f, h * 0.48f);
                    c.drawPath(hm, p);
                    c.drawRoundRect(new RectF(w * 0.24f, h * 0.42f, w * 0.76f, h * 0.88f), w * 0.06f, w * 0.06f, p);
                    break;
                }
                case MENU:
                    for (int i = 0; i < 3; i++) c.drawLine(w * 0.15f, h * (0.28f + 0.22f * i), w * 0.85f, h * (0.28f + 0.22f * i), p);
                    break;
                case DOTS:
                    for (int i = 0; i < 3; i++) c.drawCircle(w * (0.22f + 0.28f * i), h * 0.5f, w * 0.07f, f);
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
