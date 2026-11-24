package com.crimson.remote;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** A small WebSocket client (RFC 6455, text frames) for TVs that use ws:// or wss://. */
public class WsClient {
    public interface Listener {
        void onText(String text);
        void onClosed();
    }

    static final Charset UTF8 = Charset.forName("UTF-8");
    private final SecureRandom rnd = new SecureRandom();
    private Socket socket;
    private OutputStream out;
    private volatile boolean open;

    public boolean isOpen() { return open; }

    /** Connects and finishes the handshake. Blocking; the listener runs on a reader thread. */
    public void connect(String url, final Listener l) throws IOException {
        URI u = URI.create(url);
        boolean secure = "wss".equals(u.getScheme());
        int port = u.getPort() > 0 ? u.getPort() : secure ? 443 : 80;
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(u.getHost(), port), 4000);
        if (secure) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, null);
                SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, u.getHost(), port, true);
                s.startHandshake();
                socket = s;
            } catch (Exception e) {
                raw.close();
                throw new IOException("TLS failed: " + e.getMessage());
            }
        } else socket = raw;

        byte[] k = new byte[16];
        rnd.nextBytes(k);
        String key = Base64.encodeToString(k, Base64.NO_WRAP);
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        String req = "GET " + path + " HTTP/1.1\r\nHost: " + u.getHost() + ":" + port
                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key
                + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        out = socket.getOutputStream();
        out.write(req.getBytes(UTF8));
        out.flush();
        socket.setSoTimeout(6000);
        final DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        String status = readLine(in);
        if (status == null || !status.contains(" 101")) { close(); throw new IOException("TV refused the connection: " + status); }
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) { /* skip headers */ }
        socket.setSoTimeout(0);
        open = true;
        new Thread(() -> {
            try {
                StringBuilder msg = new StringBuilder();
                while (open) {
                    int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
                    int op = b0 & 0x0F;
                    long len = b1 & 0x7F;
                    if (len == 126) len = in.readUnsignedShort();
                    else if (len == 127) len = in.readLong();
                    byte[] mask = null;
                    if ((b1 & 0x80) != 0) { mask = new byte[4]; in.readFully(mask); }
                    byte[] data = new byte[(int) len];
                    in.readFully(data);
                    if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
                    if (op == 0x8) break;
                    if (op == 0x9) { frame(0xA, data); continue; }
                    if (op == 0x1 || op == 0x0) {
                        msg.append(new String(data, UTF8));
                        if ((b0 & 0x80) != 0) { l.onText(msg.toString()); msg.setLength(0); }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                boolean was = open;
                close();
                if (was) l.onClosed();
            }
        }, "ws-reader").start();
    }

    public void send(String text) throws IOException { frame(0x1, text.getBytes(UTF8)); }

    private synchronized void frame(int op, byte[] data) throws IOException {
        if (out == null) throw new IOException("Not connected");
        java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
        f.write(0x80 | op);
        if (data.length < 126) f.write(0x80 | data.length);
        else if (data.length < 65536) { f.write(0x80 | 126); f.write(data.length >> 8); f.write(data.length & 0xFF); }
        else { f.write(0x80 | 127); for (int i = 7; i >= 0; i--) f.write((int) ((long) data.length >> (8 * i)) & 0xFF); }
        byte[] m = new byte[4];
        rnd.nextBytes(m);
        f.write(m);
        for (int i = 0; i < data.length; i++) f.write(data[i] ^ m[i % 4]);
        out.write(f.toByteArray());
        out.flush();
    }

    public void close() {
        open = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    private static String readLine(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }
}
