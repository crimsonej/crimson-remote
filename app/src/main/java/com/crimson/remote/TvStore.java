package com.crimson.remote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * TVs this phone has connected to, saved when the connection is made.
 * A TV is recognised by its MAC address, so it is found again even when the
 * router gives it a new IP address.
 */
public final class TvStore {
    public static final class Tv {
        public String id;      // MAC without separators when known, otherwise "ip:<address>"
        public String ip;      // last address it answered on
        public String name;    // the TV's own name
        public String brand;   // hisense, roku, samsung, lg
        public String model;
        public String mac;     // AA:BB:CC:DD:EE:FF, for waking it up
        public long lastSeen;

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id).put("ip", ip).put("name", name).put("brand", brand)
                        .put("model", model == null ? "" : model).put("mac", mac == null ? "" : mac).put("lastSeen", lastSeen);
            } catch (Exception ignored) {}
            return o;
        }

        static Tv from(JSONObject o) {
            Tv t = new Tv();
            t.id = o.optString("id");
            t.ip = o.optString("ip");
            t.name = o.optString("name");
            t.brand = o.optString("brand", TvDriver.HISENSE);
            t.model = o.optString("model", "");
            t.mac = o.optString("mac", "");
            t.lastSeen = o.optLong("lastSeen");
            return t;
        }

        public boolean canWake() { return mac != null && mac.length() == 17; }
    }

    private final SharedPreferences prefs;

    public TvStore(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("crimson", Context.MODE_PRIVATE);
    }

    /** "18300c15099c" or "18:30:0C:15:09:9C" → "18:30:0C:15:09:9C"; anything else → "". */
    public static String normalMac(String raw) {
        if (raw == null) return "";
        String hex = raw.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (hex.length() != 12 || hex.equals("000000000000")) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 12; i += 2) { if (i > 0) b.append(':'); b.append(hex, i, i + 2); }
        return b.toString();
    }

    public static String idFor(String mac, String ip) {
        String m = normalMac(mac);
        return m.isEmpty() ? "ip:" + ip : m.replace(":", "").toLowerCase();
    }

    public synchronized List<Tv> all() {
        List<Tv> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("tvs", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Tv.from(a.getJSONObject(i)));
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized Tv get(String id) {
        if (id == null) return null;
        for (Tv t : all()) if (t.id.equals(id)) return t;
        return null;
    }

    public synchronized Tv byMac(String mac) {
        String m = normalMac(mac);
        if (m.isEmpty()) return null;
        for (Tv t : all()) if (m.equals(t.mac)) return t;
        return null;
    }

    public synchronized Tv current() { return get(prefs.getString("current", null)); }

    public synchronized void setCurrent(String id) { prefs.edit().putString("current", id).apply(); }

    /** Saves or updates a TV. An entry saved by address is merged once its MAC is known. */
    public synchronized Tv save(Tv t) {
        t.mac = normalMac(t.mac);
        String newId = idFor(t.mac, t.ip);
        List<Tv> list = all();
        JSONArray out = new JSONArray();
        Tv merged = t;
        for (Tv old : list) {
            boolean same = old.id.equals(newId) || old.id.equals(t.id)
                    || (!t.mac.isEmpty() && t.mac.equals(old.mac))
                    || (old.id.startsWith("ip:") && old.ip.equals(t.ip) && old.brand.equals(t.brand));
            if (same) {
                if (t.mac.isEmpty()) t.mac = old.mac;
                if (t.name == null || t.name.isEmpty()) t.name = old.name;
                if (t.model == null || t.model.isEmpty()) t.model = old.model;
                continue;
            }
            out.put(old.json());
        }
        merged.id = idFor(merged.mac, merged.ip);
        merged.lastSeen = System.currentTimeMillis();
        out.put(merged.json());
        String cur = prefs.getString("current", null);
        SharedPreferences.Editor e = prefs.edit().putString("tvs", out.toString());
        if (cur == null || cur.equals(t.id) || cur.startsWith("ip:") && ("ip:" + merged.ip).equals(cur)) e.putString("current", merged.id);
        e.apply();
        return merged;
    }

    public synchronized void forget(String id) {
        JSONArray out = new JSONArray();
        for (Tv t : all()) if (!t.id.equals(id)) out.put(t.json());
        SharedPreferences.Editor e = prefs.edit().putString("tvs", out.toString())
                .remove("samsung_token_" + id).remove("lg_key_" + id);
        if (id.equals(prefs.getString("current", null))) e.remove("current");
        e.apply();
    }
}
