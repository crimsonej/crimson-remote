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
    View[] pageViews = new View[3];
    TextView[] tabLabels = new TextView[3];
    View tabIndicator;
    int currentTab;

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
        Window w = getWindow();
        w.setStatusBarColor(BG_TOP);
        w.setNavigationBarColor(BG_BOTTOM);
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
    }

    // ================================================================ connection

    String ip() { return prefs.getString("ip", ""); }

    void connect() {
        setStatus(AMBER, "Connecting", true);
        net.execute(() -> {
            if (!ip().isEmpty() && tryConnect(ip())) return;
            MAIN.post(() -> setStatus(AMBER, "Looking for your TV", true));
            String found = scan();
            if (found != null) {
                prefs.edit().putString("ip", found).apply();
                if (tryConnect(found)) return;
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
                    swap(title, name);
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
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
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

    void key(String k) {
        buzz(this, 10);
        final Mqtt m = mqtt;
        if (m == null) { notConnected(); return; }
        net.execute(() -> m.publish("/remoteapp/tv/remote_service/" + cid + "/actions/sendkey", k));
    }

    void input(String code) {
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
        if (mqtt == null) { notConnected(); return; }
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
        if (items.isEmpty()) { toast("Loading inputs…"); action("ui_service", "sourcelist", ""); return; }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Choose input")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    JSONObject s = items.get(which);
                    try {
                        JSONObject o = new JSONObject();
                        o.put("sourceid", s.optString("sourceid"));
                        o.put("sourcename", s.optString("sourcename"));
                        action("ui_service", "changesource", o.toString());
                        toast("Switching to " + s.optString("displayname"));
                    } catch (Exception ignored) {}
                })
                .show();
    }

    void launchApp(JSONObject app) {
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

    void settings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 24), dp(this, 8), dp(this, 24), 0);
        final EditText ipBox = new EditText(this);
        ipBox.setHint("TV address (found automatically)");
        ipBox.setText(ip());
        ipBox.setInputType(InputType.TYPE_CLASS_PHONE);
        final EditText macBox = new EditText(this);
        macBox.setHint("TV MAC address, for power-on");
        macBox.setText(prefs.getString("mac", ""));
        box.addView(ipBox);
        box.addView(macBox);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("TV settings")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.edit().putString("ip", ipBox.getText().toString().trim())
                            .putString("mac", macBox.getText().toString().trim().toUpperCase()).apply();
                    if (mqtt != null) { mqtt.close(); mqtt = null; }
                    connect();
                })
                .setNegativeButton("Cancel", null)
                .show();
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

        // Entrance: everything rises in gently.
        root.setAlpha(0f);
        root.setTranslationY(dp(this, 18));
        root.animate().alpha(1f).translationY(0).setDuration(450)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
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
        title = text(this, prefs.getString("name", "Your TV"), 24, TEXT, 700);
        title.setPadding(0, dp(this, 6), 0, 0);
        left.addView(title);
        h.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        TextView gear = text(this, "⋯", 22, SUB, 500);
        gear.setGravity(Gravity.CENTER);
        gear.setBackground(ripple(shape(SURFACE, dp(this, 20), STROKE, this)));
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
        pill.setOnClickListener(v -> { if (mqtt == null) connect(); });
        wrap.addView(pill, margins(-2, -2, 0, 10));
        return wrap;
    }

    View nowCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(this, 18), dp(this, 16), dp(this, 18), dp(this, 12));
        GradientDrawable bg = gradient(0xFF2A0F1A, SURFACE, dp(this, 24));
        bg.setStroke(dp(this, 1), 0xFF3A1A26);
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
        volBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF3A3A48));
        volBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                volValue.setText(String.valueOf(p));
                if (System.currentTimeMillis() - lastVolSent > 120) {
                    lastVolSent = System.currentTimeMillis();
                    action("platform_service", "changevolume", String.valueOf(p));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userDraggingVol = true; buzz(MainActivity.this, 8); }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userDraggingVol = false;
                action("platform_service", "changevolume", String.valueOf(s.getProgress()));
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
        String[] names = {"Remote", "Touchpad", "Keyboard"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = text(this, names[i], 14, SUB, 500);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> { buzz(this, 8); showTab(idx, true); });
            tabLabels[i] = t;
            row.addView(t, new LinearLayout.LayoutParams(0, -1, 1));
        }
        f.addView(row, new FrameLayout.LayoutParams(-1, -1));
        f.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = (r - l - dp(this, 8)) / 3;
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
        for (int i = 0; i < 3; i++) tabLabels[i].setTextColor(i == idx ? Color.WHITE : SUB);

        for (int i = 0; i < 3; i++) {
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
        if (idx == 2) {
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
                new int[]{0xFF22222C, SURFACE});
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
                    android.content.res.ColorStateList.valueOf(0x30FFFFFF), o, null));
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
            if (domain != null) {
                new Thread(() -> {
                    final android.graphics.Bitmap bm = AppIcons.load(domain);
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
}
