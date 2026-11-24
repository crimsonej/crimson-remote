package com.crimson.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controls one TV brand. Hisense is handled by MainActivity's MQTT code; the
 * drivers here add Roku, Samsung and LG. Keys use the app's common names
 * (KEY_UP, KEY_OK, KEY_VOLUMEUP …) and text uses Lit_ codes; each driver
 * translates them for its TV.
 */
public abstract class TvDriver {
    public static final String HISENSE = "hisense", ROKU = "roku", SAMSUNG = "samsung", LG = "lg";

    public interface Listener {
        void onConnected();
        void onDisconnected();
        /** Something to show while the TV waits for the user (e.g. "Tap Allow on the TV"). */
        void onPrompt(String message);
        void onState(String title, String subtitle);
        void onVolume(int volume);
        /** [{name, id, icon?}] */
        void onApps(JSONArray apps);
        /** [{sourceid, displayname, sourcename, is_signal}] */
        void onSources(JSONArray sources);
        void onModel(String model);
    }

    static final Charset UTF8 = Charset.forName("UTF-8");
    protected final Context ctx;
    protected final String ip;
    protected final Listener l;
    protected final SharedPreferences prefs;

    TvDriver(Context c, String ip, Listener l) {
        this.ctx = c.getApplicationContext();
        this.ip = ip;
        this.l = l;
        this.prefs = ctx.getSharedPreferences("crimson", Context.MODE_PRIVATE);
    }

    /** Blocking. True when connected (pairing may still be pending, signalled with onPrompt). */
    public abstract boolean connect();
    public abstract void close();
    public abstract boolean isConnected();
    public abstract void key(String key);
    /** Lit_x, Lit_SPACE, Lit_BACKSPACE, Lit_ENTER */
    public abstract void text(String code);
    public void setVolume(int v) {}
    public boolean canSetVolume() { return false; }
    public void launch(JSONObject app) {}
    public void source(JSONObject src) {}
    public void refresh() {}
    /** Pointer support (LG). */
    public boolean hasPointer() { return false; }
    public void pointer(float dx, float dy) {}
    public void click() {}

    public static TvDriver create(Context c, String brand, String ip, Listener l) {
        switch (brand) {
            case ROKU: return new Roku(c, ip, l);
            case SAMSUNG: return new Samsung(c, ip, l);
            case LG: return new Lg(c, ip, l);
            default: return null;
        }
    }

    // ------------------------------------------------------------ discovery

    /** The ports each brand listens on, in the order to try. */
    public static final int[][] PORTS = {{36669}, {8060}, {8002, 8001}, {3001, 3000}};
    public static final String[] BRANDS = {HISENSE, ROKU, SAMSUNG, LG};

    public static boolean open(String host, int port, int timeout) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** {brand, name, model} for a host, or null if it isn't a TV this app knows. */
    public static String[] identify(String host) {
        if (open(host, 36669, 500)) {
            String xml = get("http://" + host + ":38400/MediaServer/rendererdevicedesc.xml", 2500);
            return new String[]{HISENSE, tag(xml, "friendlyName"), tag(xml, "modelName")};
        }
        if (open(host, 8060, 500)) {
            String xml = get("http://" + host + ":8060/query/device-info", 2500);
            if (xml != null && xml.contains("<device-info")) {
                String name = tag(xml, "user-device-name");
                if (name == null) name = tag(xml, "friendly-device-name");
                return new String[]{ROKU, name != null ? name : "Roku", tag(xml, "model-name")};
            }
        }
        if (open(host, 8001, 500) || open(host, 8002, 500)) {
            String js = get("http://" + host + ":8001/api/v2/", 2500);
            if (js != null && js.contains("\"device\"")) {
                try {
                    JSONObject d = new JSONObject(js).getJSONObject("device");
                    return new String[]{SAMSUNG, d.optString("name", "Samsung TV").replace("[TV] ", ""), d.optString("modelName", null)};
                } catch (Exception ignored) {}
            }
        }
        if (open(host, 3000, 500) || open(host, 3001, 500)) {
            return new String[]{LG, "LG TV", null};
        }
        return null;
    }

    // ------------------------------------------------------------ helpers

    static String get(String url, int timeout) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            return read(c.getInputStream());
        } catch (Exception e) {
            return null;
        }
    }

    static int post(String url, String body) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.getOutputStream().write(body == null ? new byte[0] : body.getBytes(UTF8));
            int code = c.getResponseCode();
            c.disconnect();
            return code;
        } catch (Exception e) {
            return -1;
        }
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    static String tag(String xml, String t) {
        if (xml == null) return null;
        int a = xml.indexOf("<" + t + ">");
        if (a < 0) return null;
        int b = xml.indexOf("</" + t + ">", a);
        if (b < 0) return null;
        String v = xml.substring(a + t.length() + 2, b).trim();
        return v.isEmpty() ? null : v;
    }

    static void bg(Runnable r) { new Thread(r).start(); }

    /** Single printable character from a Lit_ code, or null for a special code. */
    static String litChar(String code) {
        String v = code.substring(4);
        if (v.equals("SPACE")) return " ";
        return v.length() == 1 || Character.charCount(v.codePointAt(0)) == v.length() ? v : null;
    }

    // ============================================================ Roku

    /** Roku and Roku TVs: External Control Protocol over HTTP on port 8060, no pairing. */
    static class Roku extends TvDriver {
        private volatile boolean up;
        private Thread poller;
        static final Map<String, String> KEYS = new HashMap<>();
        static {
            String[][] m = {{"KEY_POWER", "Power"}, {"KEY_UP", "Up"}, {"KEY_DOWN", "Down"}, {"KEY_LEFT", "Left"},
                    {"KEY_RIGHT", "Right"}, {"KEY_OK", "Select"}, {"KEY_RETURNS", "Back"}, {"KEY_HOME", "Home"},
                    {"KEY_MENU", "Info"}, {"KEY_EXIT", "Home"}, {"KEY_INFO", "Info"}, {"KEY_VOLUMEUP", "VolumeUp"},
                    {"KEY_VOLUMEDOWN", "VolumeDown"}, {"KEY_MUTE", "VolumeMute"}, {"KEY_CHANNELUP", "ChannelUp"},
                    {"KEY_CHANNELDOWN", "ChannelDown"}, {"KEY_PLAY", "Play"}, {"KEY_PAUSE", "Play"},
                    {"KEY_BACK", "Rev"}, {"KEY_FORWARDS", "Fwd"}, {"KEY_SUBTITLE", "InstantReplay"}};
            for (String[] p : m) KEYS.put(p[0], p[1]);
        }

        Roku(Context c, String ip, Listener l) { super(c, ip, l); }

        String base() { return "http://" + ip + ":8060"; }

        @Override public boolean connect() {
            String info = get(base() + "/query/device-info", 3000);
            if (info == null) return false;
            up = true;
            String model = tag(info, "model-name");
            String name = tag(info, "user-device-name");
            l.onModel("Roku " + (model != null ? model : ""));
            l.onConnected();
            if ("true".equals(tag(info, "is-tv"))) {
                JSONArray src = new JSONArray();
                String[][] inputs = {{"InputTuner", "TV"}, {"InputHDMI1", "HDMI 1"}, {"InputHDMI2", "HDMI 2"},
                        {"InputHDMI3", "HDMI 3"}, {"InputHDMI4", "HDMI 4"}, {"InputAV1", "AV"}};
                for (String[] s : inputs) {
                    try { src.put(new JSONObject().put("sourceid", s[0]).put("displayname", s[1]).put("sourcename", s[1]).put("is_signal", "0")); } catch (Exception ignored) {}
                }
                l.onSources(src);
            }
            refresh();
            poller = new Thread(() -> {
                while (up) {
                    pollActive();
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                }
            });
            poller.start();
            if (name != null) l.onState(name, "Roku");
            return true;
        }

        void pollActive() {
            String xml = get(base() + "/query/active-app", 2500);
            if (xml == null) { if (up) { up = false; l.onDisconnected(); } return; }
            int a = xml.indexOf("<app");
            if (a < 0) return;
            int s = xml.indexOf('>', a), e = xml.indexOf("</app>", s);
            if (s < 0 || e < 0) return;
            String app = xml.substring(s + 1, e).trim();
            l.onState(app.isEmpty() || app.equals("Roku") ? "Home screen" : app, "Roku");
        }

        @Override public void refresh() {
            bg(() -> {
                String xml = get(base() + "/query/apps", 3000);
                if (xml == null) return;
                JSONArray apps = new JSONArray();
                int i = 0;
                while ((i = xml.indexOf("<app ", i)) >= 0) {
                    int idA = xml.indexOf("id=\"", i), idB = xml.indexOf('"', idA + 4);
                    int s = xml.indexOf('>', i), e = xml.indexOf("</app>", s);
                    if (idA < 0 || s < 0 || e < 0) break;
                    String id = xml.substring(idA + 4, idB);
                    String name = xml.substring(s + 1, e).replace("&amp;", "&").trim();
                    try { apps.put(new JSONObject().put("name", name).put("id", id).put("icon", base() + "/query/icon/" + id)); } catch (Exception ignored) {}
                    i = e;
                }
                l.onApps(apps);
            });
        }

        @Override public void close() { up = false; if (poller != null) poller.interrupt(); }
        @Override public boolean isConnected() { return up; }

        @Override public void key(String key) {
            String k = KEYS.get(key);
            if (k == null && key.startsWith("KEY_") && key.length() == 5 && Character.isDigit(key.charAt(4)))
                k = "Lit_" + key.charAt(4);
            if (k == null) return;
            final String kk = k;
            bg(() -> post(base() + "/keypress/" + kk, null));
        }

        @Override public void text(String code) {
            String k;
            if (code.equals("Lit_BACKSPACE")) k = "Backspace";
            else if (code.equals("Lit_ENTER")) k = "Enter";
            else {
                String ch = litChar(code);
                if (ch == null) return;
                try { k = "Lit_" + URLEncoder.encode(ch, "UTF-8").replace("+", "%20"); } catch (Exception e) { return; }
            }
            final String kk = k;
            bg(() -> post(base() + "/keypress/" + kk, null));
        }

        @Override public void launch(JSONObject app) {
            final String id = app.optString("id");
            bg(() -> post(base() + "/launch/" + id, null));
        }

        @Override public void source(JSONObject src) {
            final String id = src.optString("sourceid");
            bg(() -> post(base() + "/keypress/" + id, null));
        }
    }

    // ============================================================ Samsung

    /** Samsung Tizen TVs (2016+): WebSocket remote API. The TV asks the user to Allow once. */
    static class Samsung extends TvDriver {
        private final WsClient ws = new WsClient();
        private volatile boolean ready;
        static final Map<String, String> KEYS = new HashMap<>();
        static {
            String[][] m = {{"KEY_OK", "KEY_ENTER"}, {"KEY_RETURNS", "KEY_RETURN"}, {"KEY_VOLUMEUP", "KEY_VOLUP"},
                    {"KEY_VOLUMEDOWN", "KEY_VOLDOWN"}, {"KEY_CHANNELUP", "KEY_CHUP"}, {"KEY_CHANNELDOWN", "KEY_CHDOWN"},
                    {"KEY_BACK", "KEY_REWIND"}, {"KEY_FORWARDS", "KEY_FF"}, {"KEY_SUBTITLE", "KEY_SUB_TITLE"},
                    {"KEY_BLUE", "KEY_CYAN"}};
            for (String[] p : m) KEYS.put(p[0], p[1]);
        }

        Samsung(Context c, String ip, Listener l) { super(c, ip, l); }

        @Override public boolean connect() {
            String name = Base64.encodeToString("Crimson Remote".getBytes(UTF8), Base64.NO_WRAP);
            String token = prefs.getString("samsung_token_" + ip, "");
            String[] urls = {
                    "wss://" + ip + ":8002/api/v2/channels/samsung.remote.control?name=" + name + (token.isEmpty() ? "" : "&token=" + token),
                    "ws://" + ip + ":8001/api/v2/channels/samsung.remote.control?name=" + name};
            for (String u : urls) {
                try {
                    ws.connect(u, new WsClient.Listener() {
                        @Override public void onText(String t) { handle(t); }
                        @Override public void onClosed() { ready = false; l.onDisconnected(); }
                    });
                    if (token.isEmpty()) l.onPrompt("Tap Allow on the TV to let Crimson control it");
                    String js = get("http://" + ip + ":8001/api/v2/", 2500);
                    try {
                        JSONObject d = new JSONObject(js).getJSONObject("device");
                        l.onModel("Samsung " + d.optString("modelName", ""));
                        String mac = d.optString("wifiMac", "");
                        if (!mac.isEmpty() && prefs.getString("mac", "").isEmpty()) prefs.edit().putString("mac", mac.toUpperCase()).apply();
                    } catch (Exception ignored) {}
                    return true;
                } catch (IOException ignored) {}
            }
            return false;
        }

        private void handle(String text) {
            try {
                JSONObject o = new JSONObject(text);
                String ev = o.optString("event");
                if (ev.equals("ms.channel.connect")) {
                    JSONObject d = o.optJSONObject("data");
                    if (d != null && d.has("token")) prefs.edit().putString("samsung_token_" + ip, d.getString("token")).apply();
                    ready = true;
                    l.onConnected();
                    l.onState("Samsung TV", "Connected");
                    refresh();
                } else if (ev.equals("ms.channel.unauthorized")) {
                    l.onPrompt("The TV said no. Allow Crimson in the TV's device connection settings, then retry.");
                } else if (ev.equals("ed.installedApp.get")) {
                    JSONArray list = o.getJSONObject("data").getJSONArray("data");
                    JSONArray apps = new JSONArray();
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject a = list.getJSONObject(i);
                        apps.put(new JSONObject().put("name", a.optString("name")).put("id", a.optString("appId"))
                                .put("app_type", a.optInt("app_type", 2)));
                    }
                    l.onApps(apps);
                }
            } catch (Exception ignored) {}
        }

        private void send(JSONObject o) { bg(() -> { try { ws.send(o.toString()); } catch (IOException ignored) {} }); }

        @Override public void refresh() {
            try {
                send(new JSONObject().put("method", "ms.channel.emit").put("params",
                        new JSONObject().put("event", "ed.installedApp.get").put("to", "host")));
            } catch (Exception ignored) {}
        }

        @Override public void close() { ready = false; ws.close(); }
        @Override public boolean isConnected() { return ws.isOpen(); }

        @Override public void key(String key) {
            if (key.equals("KEY_LEFTMOUSEKEYS")) return;
            String k = KEYS.containsKey(key) ? KEYS.get(key) : key;
            try {
                send(new JSONObject().put("method", "ms.remote.control").put("params", new JSONObject()
                        .put("Cmd", "Click").put("DataOfCmd", k).put("Option", "false").put("TypeOfRemote", "SendRemoteKey")));
            } catch (Exception ignored) {}
        }

        @Override public void text(String code) {
            if (code.equals("Lit_BACKSPACE")) { key("KEY_BACKSPACE"); return; }
            if (code.equals("Lit_ENTER")) {
                try {
                    send(new JSONObject().put("method", "ms.remote.control").put("params",
                            new JSONObject().put("TypeOfRemote", "SendInputEnd")));
                } catch (Exception ignored) {}
                return;
            }
            String ch = litChar(code);
            if (ch == null) return;
            try {
                send(new JSONObject().put("method", "ms.remote.control").put("params", new JSONObject()
                        .put("Cmd", Base64.encodeToString(ch.getBytes(UTF8), Base64.NO_WRAP))
                        .put("DataOfCmd", "base64").put("TypeOfRemote", "SendInputString")));
            } catch (Exception ignored) {}
        }

        @Override public void launch(JSONObject app) {
            try {
                send(new JSONObject().put("method", "ms.channel.emit").put("params", new JSONObject()
                        .put("event", "ed.apps.launch").put("to", "host").put("data", new JSONObject()
                                .put("appId", app.optString("id"))
                                .put("action_type", app.optInt("app_type", 2) == 2 ? "DEEP_LINK" : "NATIVE_LAUNCH"))));
            } catch (Exception ignored) {}
        }

        @Override public void source(JSONObject src) { key("KEY_SOURCE"); }
    }

    // ============================================================ LG

    /** LG webOS TVs: SSAP over WebSocket. The TV asks the user to Accept once. */
    static class Lg extends TvDriver {
        private final WsClient ws = new WsClient();
        private final WsClient pointerWs = new WsClient();
        private final AtomicInteger ids = new AtomicInteger(1);
        private final Map<String, String> pending = new ConcurrentHashMap<>();
        private volatile boolean registered;
        static final Map<String, String> BUTTONS = new HashMap<>();
        static {
            String[][] m = {{"KEY_UP", "UP"}, {"KEY_DOWN", "DOWN"}, {"KEY_LEFT", "LEFT"}, {"KEY_RIGHT", "RIGHT"},
                    {"KEY_OK", "ENTER"}, {"KEY_RETURNS", "BACK"}, {"KEY_HOME", "HOME"}, {"KEY_MENU", "MENU"},
                    {"KEY_EXIT", "EXIT"}, {"KEY_INFO", "INFO"}, {"KEY_RED", "RED"}, {"KEY_GREEN", "GREEN"},
                    {"KEY_YELLOW", "YELLOW"}, {"KEY_BLUE", "BLUE"}, {"KEY_SUBTITLE", "CC"},
                    {"KEY_CHANNELUP", "CHANNELUP"}, {"KEY_CHANNELDOWN", "CHANNELDOWN"}};
            for (String[] p : m) BUTTONS.put(p[0], p[1]);
            for (int i = 0; i <= 9; i++) BUTTONS.put("KEY_" + i, String.valueOf(i));
        }

        Lg(Context c, String ip, Listener l) { super(c, ip, l); }

        @Override public boolean connect() {
            String[] urls = {"wss://" + ip + ":3001/", "ws://" + ip + ":3000/"};
            for (String u : urls) {
                try {
                    ws.connect(u, new WsClient.Listener() {
                        @Override public void onText(String t) { handle(t); }
                        @Override public void onClosed() { registered = false; pointerWs.close(); l.onDisconnected(); }
                    });
                    register();
                    return true;
                } catch (IOException ignored) {}
            }
            return false;
        }

        private void register() throws IOException {
            try {
                JSONObject payload = new JSONObject(TvDriver.read(ctx.getAssets().open("lg_manifest.json")));
                String key = prefs.getString("lg_key_" + ip, "");
                if (!key.isEmpty()) payload.put("client-key", key);
                else l.onPrompt("Tap Accept on the TV to let Crimson control it");
                ws.send(new JSONObject().put("type", "register").put("id", "register_0").put("payload", payload).toString());
            } catch (Exception e) {
                throw new IOException(e.getMessage());
            }
        }

        private String request(String uri, JSONObject payload, String tag) {
            String id = tag + "_" + ids.getAndIncrement();
            if (tag != null) pending.put(id, tag);
            try {
                JSONObject o = new JSONObject().put("type", "request").put("id", id).put("uri", uri);
                if (payload != null) o.put("payload", payload);
                final String s = o.toString();
                bg(() -> { try { ws.send(s); } catch (IOException ignored) {} });
            } catch (Exception ignored) {}
            return id;
        }

        private void subscribe(String uri, String tag) {
            String id = tag + "_sub";
            pending.put(id, tag);
            try {
                final String s = new JSONObject().put("type", "subscribe").put("id", id).put("uri", uri).toString();
                bg(() -> { try { ws.send(s); } catch (IOException ignored) {} });
            } catch (Exception ignored) {}
        }

        private void handle(String text) {
            try {
                JSONObject o = new JSONObject(text);
                String type = o.optString("type"), id = o.optString("id");
                JSONObject p = o.optJSONObject("payload");
                if (id.equals("register_0")) {
                    if (type.equals("registered")) {
                        if (p != null && p.has("client-key")) prefs.edit().putString("lg_key_" + ip, p.getString("client-key")).apply();
                        registered = true;
                        l.onConnected();
                        afterRegister();
                    } else if (type.equals("error")) {
                        l.onPrompt("The TV didn't accept Crimson. Try again and choose Accept on the TV.");
                    }
                    return;
                }
                if (p == null) return;
                String tag = pending.get(id);
                if (tag == null) return;
                switch (tag) {
                    case "apps": {
                        JSONArray list = p.optJSONArray("apps");
                        if (list == null) break;
                        JSONArray apps = new JSONArray();
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject a = list.getJSONObject(i);
                            if (!a.optBoolean("visible", true)) continue;
                            apps.put(new JSONObject().put("name", a.optString("title")).put("id", a.optString("id"))
                                    .put("icon", a.optString("icon", "")));
                        }
                        l.onApps(apps);
                        break;
                    }
                    case "inputs": {
                        JSONArray list = p.optJSONArray("devices");
                        if (list == null) break;
                        JSONArray src = new JSONArray();
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject d = list.getJSONObject(i);
                            src.put(new JSONObject().put("sourceid", d.optString("id")).put("displayname", d.optString("label"))
                                    .put("sourcename", d.optString("id")).put("is_signal", d.optBoolean("connected") ? "1" : "0"));
                        }
                        l.onSources(src);
                        break;
                    }
                    case "volume": {
                        int v = p.has("volume") ? p.optInt("volume", -1) : p.optJSONObject("volumeStatus") != null
                                ? p.optJSONObject("volumeStatus").optInt("volume", -1) : -1;
                        if (v >= 0) l.onVolume(v);
                        break;
                    }
                    case "fg": {
                        String app = p.optString("appId", "");
                        l.onState(app.isEmpty() ? "LG TV" : pretty(app), "LG webOS");
                        break;
                    }
                    case "info":
                        l.onModel("LG " + p.optString("modelName", ""));
                        break;
                    case "pointer": {
                        String path = p.optString("socketPath", "");
                        if (!path.isEmpty()) bg(() -> { try { pointerWs.connect(path, new WsClient.Listener() {
                            @Override public void onText(String t) {}
                            @Override public void onClosed() {}
                        }); } catch (IOException ignored) {} });
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }

        static String pretty(String appId) {
            String s = appId.substring(appId.lastIndexOf('.') + 1);
            return s.isEmpty() ? appId : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        private void afterRegister() {
            request("ssap://com.webos.service.networkinput/getPointerInputSocket", null, "pointer");
            request("ssap://system/getSystemInfo", null, "info");
            subscribe("ssap://audio/getVolume", "volume");
            subscribe("ssap://com.webos.applicationManager/getForegroundAppInfo", "fg");
            refresh();
        }

        @Override public void refresh() {
            if (!registered) return;
            request("ssap://com.webos.applicationManager/listApps", null, "apps");
            request("ssap://tv/getExternalInputList", null, "inputs");
        }

        @Override public void close() { registered = false; pointerWs.close(); ws.close(); }
        @Override public boolean isConnected() { return ws.isOpen(); }

        private void button(String name) {
            final String s = "type:button\nname:" + name + "\n\n";
            bg(() -> { try { pointerWs.send(s); } catch (IOException ignored) {} });
        }

        @Override public void key(String key) {
            switch (key) {
                case "KEY_VOLUMEUP": request("ssap://audio/volumeUp", null, null); return;
                case "KEY_VOLUMEDOWN": request("ssap://audio/volumeDown", null, null); return;
                case "KEY_MUTE": button("MUTE"); return;
                case "KEY_POWER": request("ssap://system/turnOff", null, null); return;
                case "KEY_PLAY": request("ssap://media.controls/play", null, null); return;
                case "KEY_PAUSE": request("ssap://media.controls/pause", null, null); return;
                case "KEY_BACK": request("ssap://media.controls/rewind", null, null); return;
                case "KEY_FORWARDS": request("ssap://media.controls/fastForward", null, null); return;
            }
            String b = BUTTONS.get(key);
            if (b != null) button(b);
        }

        @Override public void text(String code) {
            try {
                if (code.equals("Lit_BACKSPACE")) request("ssap://com.webos.service.ime/deleteCharacters", new JSONObject().put("count", 1), null);
                else if (code.equals("Lit_ENTER")) request("ssap://com.webos.service.ime/sendEnterKey", null, null);
                else {
                    String ch = litChar(code);
                    if (ch != null) request("ssap://com.webos.service.ime/insertText", new JSONObject().put("text", ch).put("replace", 0), null);
                }
            } catch (Exception ignored) {}
        }

        @Override public boolean canSetVolume() { return true; }
        @Override public void setVolume(int v) {
            try { request("ssap://audio/setVolume", new JSONObject().put("volume", v), null); } catch (Exception ignored) {}
        }

        @Override public void launch(JSONObject app) {
            try { request("ssap://system.launcher/launch", new JSONObject().put("id", app.optString("id")), null); } catch (Exception ignored) {}
        }

        @Override public void source(JSONObject src) {
            try { request("ssap://tv/switchInput", new JSONObject().put("inputId", src.optString("sourceid")), null); } catch (Exception ignored) {}
        }

        @Override public boolean hasPointer() { return true; }
        @Override public void pointer(float dx, float dy) {
            final String s = "type:move\ndx:" + Math.round(dx) + "\ndy:" + Math.round(dy) + "\ndown:0\n\n";
            bg(() -> { try { pointerWs.send(s); } catch (IOException ignored) {} });
        }
        @Override public void click() {
            bg(() -> { try { pointerWs.send("type:click\n\n"); } catch (IOException ignored) {} });
        }
    }
}
