package com.crimson.remote;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Casts photos, videos and music to the TV over the Wi-Fi with DLNA (UPnP AV):
 * the phone serves the file on a small web server and tells the TV's media
 * renderer to play that address.
 */
public class Caster {
    static final int PORT = 8790;
    static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context ctx;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile Uri current;
    private volatile String currentType = "application/octet-stream";
    private volatile long currentSize = -1;
    private String controlUrl, renderingUrl;

    public Caster(Context c) { ctx = c.getApplicationContext(); }

    // ------------------------------------------------------------ public API

    /** Finds the TV's AVTransport control address. Blocking. */
    public boolean prepare(String tvIp) {
        if (controlUrl != null && controlUrl.contains(tvIp)) return true;
        try {
            String base = "http://" + tvIp + ":38400";
            String xml = get(base + "/MediaServer/rendererdevicedesc.xml");
            controlUrl = absolute(base, serviceControl(xml, "AVTransport"));
            String rc = serviceControl(xml, "RenderingControl");
            renderingUrl = rc == null ? null : absolute(base, rc);
            return controlUrl != null;
        } catch (Exception e) {
            controlUrl = null;
            return false;
        }
    }

    /** Serves the chosen file and asks the TV to play it. Blocking; throws with a readable message. */
    public void cast(String tvIp, Uri uri) throws Exception {
        if (!prepare(tvIp)) throw new IOException("This TV doesn't accept casting right now");
        startServer();
        ContentResolver cr = ctx.getContentResolver();
        current = uri;
        String type = cr.getType(uri);
        currentType = type == null ? "application/octet-stream" : type;
        currentSize = size(uri);
        String phone = Bridge.localIp();
        if (phone == null) throw new IOException("Connect the phone to Wi-Fi first");
        String ext = currentType.startsWith("image/") ? ".jpg" : currentType.startsWith("audio/") ? ".mp3" : ".mp4";
        String url = "http://" + phone + ":" + PORT + "/media/" + System.currentTimeMillis() + ext;

        try { soap(controlUrl, "AVTransport", "Stop", "<InstanceID>0</InstanceID>"); } catch (Exception ignored) {}
        soap(controlUrl, "AVTransport", "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>" + esc(url) + "</CurrentURI>"
                        + "<CurrentURIMetaData>" + esc(didl(url, displayName(uri))) + "</CurrentURIMetaData>");
        soap(controlUrl, "AVTransport", "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    public void play() throws Exception { soap(controlUrl, "AVTransport", "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>"); }
    public void pause() throws Exception { soap(controlUrl, "AVTransport", "Pause", "<InstanceID>0</InstanceID>"); }
    public void stop() throws Exception { soap(controlUrl, "AVTransport", "Stop", "<InstanceID>0</InstanceID>"); }

    public String kind() {
        return currentType.startsWith("image/") ? "photo" : currentType.startsWith("audio/") ? "music" : "video";
    }

    public void shutdown() {
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        server = null;
    }

    // ------------------------------------------------------------ UPnP

    private String didl(String url, String title) {
        String cls = currentType.startsWith("image/") ? "object.item.imageItem.photo"
                : currentType.startsWith("audio/") ? "object.item.audioItem.musicTrack" : "object.item.videoItem";
        String size = currentSize > 0 ? " size=\"" + currentSize + "\"" : "";
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"1\" parentID=\"0\" restricted=\"1\"><dc:title>" + esc(title) + "</dc:title>"
                + "<upnp:class>" + cls + "</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + currentType + ":*\"" + size + ">" + esc(url) + "</res>"
                + "</item></DIDL-Lite>";
    }

    private static String soap(String url, String service, String action, String args) throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"urn:schemas-upnp-org:service:" + service + ":1\">" + args
                + "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:" + service + ":1#" + action + "\"");
        c.getOutputStream().write(body.getBytes(UTF8));
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String resp = in == null ? "" : read(in);
        if (code >= 400) throw new IOException("The TV refused " + action + " (" + code + ")");
        return resp;
    }

    /** The controlURL of a service in a UPnP device description. */
    static String serviceControl(String xml, String service) {
        int i = xml.indexOf(":service:" + service + ":");
        if (i < 0) return null;
        int a = xml.indexOf("<controlURL>", i);
        int b = xml.indexOf("</controlURL>", a);
        if (a < 0 || b < 0) return null;
        return xml.substring(a + 12, b).trim();
    }

    static String absolute(String base, String path) {
        if (path == null) return null;
        if (path.startsWith("http")) return path;
        return base + (path.startsWith("/") ? "" : "/") + path;
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(4000);
        return read(c.getInputStream());
    }

    static String read(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ------------------------------------------------------------ file server

    private synchronized void startServer() throws IOException {
        if (server != null && !server.isClosed()) return;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(PORT));
        pool.execute(() -> {
            while (server != null && !server.isClosed()) {
                try {
                    Socket s = server.accept();
                    pool.execute(() -> serve(s));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(20000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String request = line(in);
            if (request == null) return;
            String method = request.split(" ")[0];
            long from = 0, to = -1;
            String l;
            while ((l = line(in)) != null && !l.isEmpty()) {
                if (l.toLowerCase().startsWith("range:")) {
                    String r = l.substring(6).trim().replace("bytes=", "");
                    String[] p = r.split("-");
                    try { from = Long.parseLong(p[0].trim()); } catch (Exception ignored) {}
                    if (p.length > 1 && !p[1].trim().isEmpty()) try { to = Long.parseLong(p[1].trim()); } catch (Exception ignored) {}
                }
            }
            OutputStream out = s.getOutputStream();
            Uri uri = current;
            if (uri == null) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(UTF8)); return; }
            long size = currentSize;
            boolean partial = from > 0 || to >= 0;
            if (size > 0 && to < 0) to = size - 1;
            long length = size > 0 ? to - from + 1 : -1;
            StringBuilder h = new StringBuilder();
            h.append(partial && size > 0 ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: ").append(currentType).append("\r\n");
            if (length >= 0) h.append("Content-Length: ").append(length).append("\r\n");
            if (partial && size > 0) h.append("Content-Range: bytes ").append(from).append('-').append(to).append('/').append(size).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            h.append("transferMode.dlna.org: ").append(currentType.startsWith("image/") ? "Interactive" : "Streaming").append("\r\n");
            h.append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes(UTF8));
            if (method.equals("HEAD")) return;
            try (InputStream file = ctx.getContentResolver().openInputStream(uri)) {
                if (file == null) return;
                long skipped = 0;
                while (skipped < from) {
                    long k = file.skip(from - skipped);
                    if (k <= 0) break;
                    skipped += k;
                }
                byte[] buf = new byte[64 * 1024];
                long left = length >= 0 ? length : Long.MAX_VALUE;
                int n;
                while (left > 0 && (n = file.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }

    private long size(Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {}
        return -1;
    }

    String displayName(Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {}
        return "Crimson cast";
    }
}
