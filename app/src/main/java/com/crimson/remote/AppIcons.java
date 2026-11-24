package com.crimson.remote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Real logos for TV apps, fetched from each service's own site icon. */
final class AppIcons {
    private static final Map<String, Bitmap> cache = new HashMap<>();

    private AppIcons() {}

    static String domain(String name, String url) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("youtube")) return "youtube.com";
        if (n.contains("netflix")) return "netflix.com";
        if (n.contains("prime")) return "primevideo.com";
        if (n.contains("dstv")) return "dstv.com";
        if (n.contains("showmax")) return "showmax.com";
        if (n.contains("accuweather")) return "accuweather.com";
        if (n.contains("mindset")) return "mindset.africa";
        if (n.contains("appsnow") || n.contains("app store")) return "vidaa.com";
        if (n.contains("toon goggles")) return "toongoggles.com";
        if (n.contains("yupp")) return "yupptv.com";
        if (n.contains("spotify")) return "spotify.com";
        if (n.contains("disney")) return "disneyplus.com";
        if (n.contains("deezer")) return "deezer.com";
        if (n.contains("facebook")) return "facebook.com";
        if (n.contains("tiktok")) return "tiktok.com";
        if (n.contains("plex")) return "plex.tv";
        if (n.contains("pluto")) return "pluto.tv";
        if (n.contains("apple tv")) return "tv.apple.com";
        if (url != null && url.startsWith("http")) {
            try {
                String host = new URL(url).getHost();
                String[] p = host.split("\\.");
                if (p.length >= 2) return p[p.length - 2] + "." + p[p.length - 1];
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Any image URL (e.g. an icon the TV serves). Blocking. */
    static Bitmap loadUrl(String url) {
        synchronized (cache) { if (cache.containsKey(url)) return cache.get(url); }
        Bitmap b = null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(5000);
            try (InputStream in = c.getInputStream()) { b = BitmapFactory.decodeStream(in); }
        } catch (Exception ignored) {}
        synchronized (cache) { cache.put(url, b); }
        return b;
    }

    /** Blocking; call off the main thread. Returns null when there is no logo. */
    static Bitmap load(String domain) {
        if (domain == null) return null;
        synchronized (cache) { if (cache.containsKey(domain)) return cache.get(domain); }
        Bitmap b = null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://www.google.com/s2/favicons?sz=128&domain=" + domain).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            try (InputStream in = c.getInputStream()) { b = BitmapFactory.decodeStream(in); }
            // The service returns a 16px globe when a site has no icon; treat that as "no logo".
            if (b != null && b.getWidth() < 32) b = null;
        } catch (Exception ignored) {}
        synchronized (cache) { cache.put(domain, b); }
        return b;
    }
}
