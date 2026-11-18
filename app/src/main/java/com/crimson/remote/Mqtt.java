package com.crimson.remote;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Tiny MQTT 3.1.1 client (QoS 0 only) for the Hisense VIDAA remote service. */
public class Mqtt {
    public interface Listener {
        void onConnected();
        void onDisconnected(String why);
        void onMessage(String topic, String payload);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final String host, clientId, user, pass;
    private final int port;
    private final Listener listener;
    private SSLSocket socket;
    private OutputStream out;
    private volatile boolean running;
    private int packetId = 1;

    public Mqtt(String host, int port, String clientId, String user, String pass, Listener l) {
        this.host = host; this.port = port; this.clientId = clientId;
        this.user = user; this.pass = pass; this.listener = l;
    }

    public boolean isConnected() { return running && socket != null && socket.isConnected(); }

    public void connect() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket();
        s.connect(new InetSocketAddress(host, port), 4000);
        s.setSoTimeout(0);
        s.startHandshake();
        socket = s;
        out = s.getOutputStream();

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        str(b, "MQTT");
        b.write(4);              // protocol level 3.1.1
        b.write(0xC2);           // username + password + clean session
        b.write(0); b.write(30); // keepalive 30 s
        str(b, clientId); str(b, user); str(b, pass);
        send(0x10, b.toByteArray());

        DataInputStream in = new DataInputStream(s.getInputStream());
        int h = in.readUnsignedByte();
        int len = readLen(in);
        byte[] body = new byte[len];
        in.readFully(body);
        if ((h >> 4) != 2 || len < 2 || body[1] != 0) {
            close();
            throw new IOException("TV refused the connection");
        }
        running = true;
        startReader(in);
        startPinger();
        listener.onConnected();
    }

    public void subscribe(String topic) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int id = packetId++;
            b.write(id >> 8); b.write(id & 0xFF);
            str(b, topic); b.write(0);
            send(0x82, b.toByteArray());
        } catch (IOException e) { fail(e); }
    }

    public void publish(String topic, String payload) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            str(b, topic);
            b.write(payload.getBytes(UTF8));
            send(0x30, b.toByteArray());
        } catch (IOException e) { fail(e); }
    }

    public void close() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
    }

    private synchronized void send(int header, byte[] body) throws IOException {
        if (out == null) throw new IOException("not connected");
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(header);
        int len = body.length;
        do {
            int d = len % 128; len /= 128;
            if (len > 0) d |= 0x80;
            p.write(d);
        } while (len > 0);
        p.write(body);
        out.write(p.toByteArray());
        out.flush();
    }

    private void startReader(final DataInputStream in) {
        new Thread(new Runnable() { public void run() {
            try {
                while (running) {
                    int h = in.readUnsignedByte();
                    int len = readLen(in);
                    byte[] body = new byte[len];
                    in.readFully(body);
                    if ((h >> 4) == 3) {
                        int tl = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
                        String topic = new String(body, 2, tl, UTF8);
                        int off = 2 + tl;
                        if (((h >> 1) & 3) > 0) off += 2;
                        String payload = new String(body, off, body.length - off, UTF8);
                        listener.onMessage(topic, payload);
                    }
                }
            } catch (IOException e) { fail(e); }
        }}, "mqtt-reader").start();
    }

    private void startPinger() {
        new Thread(new Runnable() { public void run() {
            while (running) {
                try { Thread.sleep(20000); } catch (InterruptedException e) { return; }
                if (!running) return;
                try { send(0xC0, new byte[0]); } catch (IOException e) { fail(e); return; }
            }
        }}, "mqtt-ping").start();
    }

    private void fail(Exception e) {
        if (!running) return;
        close();
        listener.onDisconnected(e.getMessage() == null ? "Connection lost" : e.getMessage());
    }

    private static int readLen(DataInputStream in) throws IOException {
        int mult = 1, value = 0, d;
        do {
            d = in.readUnsignedByte();
            value += (d & 127) * mult;
            mult *= 128;
        } while ((d & 128) != 0);
        return value;
    }

    private static void str(ByteArrayOutputStream b, String s) throws IOException {
        byte[] d = s.getBytes(UTF8);
        b.write(d.length >> 8); b.write(d.length & 0xFF);
        b.write(d);
    }
}
