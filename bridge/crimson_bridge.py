#!/usr/bin/env python3
"""
Crimson Bridge — connects the Crimson Remote web app to a Hisense VIDAA TV.

Browsers can't open the TV's MQTT-over-TLS port, so this small server sits on
your home network, keeps one connection to the TV, and exposes a simple HTTP
API (plus a live event stream) that the web app uses. It also serves the web
app itself, so you can open http://<this-device>:8787 on your phone.

Python 3.8+ standard library only. Run:  python3 crimson_bridge.py
"""
import json
import os
import queue
import random
import socket
import ssl
import string
import struct
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

PORT = int(os.environ.get("CRIMSON_PORT", "8787"))
TV_PORT = 36669
TOKEN = os.environ.get("CRIMSON_TOKEN", "")          # optional; required when exposed to the internet
CONFIG = os.path.expanduser(os.environ.get("CRIMSON_CONFIG", "~/.crimson_bridge.json"))
WEB_DIR = os.path.abspath(os.environ.get("CRIMSON_WEB", os.path.join(os.path.dirname(__file__), "..", "web")))
MQTT_USER, MQTT_PASS = "hisenseservice", "multimqttservice"


# ----------------------------------------------------------------- config

def load_config():
    try:
        with open(CONFIG) as f:
            cfg = json.load(f)
    except Exception:
        cfg = {}
    if not cfg.get("cid"):
        cfg["cid"] = "CrimsonBridge-" + "".join(random.choices(string.ascii_lowercase + string.digits, k=8))
    for k in ("ip", "mac"):
        if os.environ.get("CRIMSON_TV_" + k.upper()):
            cfg[k] = os.environ["CRIMSON_TV_" + k.upper()]
    save_config(cfg)
    return cfg


def save_config(cfg):
    try:
        with open(CONFIG, "w") as f:
            json.dump(cfg, f, indent=2)
        os.chmod(CONFIG, 0o600)
    except Exception as e:
        print("could not save config:", e, file=sys.stderr)


# ----------------------------------------------------------------- tiny MQTT client

class Mqtt:
    def __init__(self, host, cid, on_message, on_close):
        self.host, self.cid = host, cid
        self.on_message, self.on_close = on_message, on_close
        self.sock = None
        self.lock = threading.Lock()
        self.alive = False

    @staticmethod
    def _str(s):
        b = s.encode()
        return struct.pack("!H", len(b)) + b

    def _send(self, header, body):
        n, enc = len(body), bytearray()
        while True:
            d, n = n % 128, n // 128
            enc.append(d | (0x80 if n else 0))
            if not n:
                break
        with self.lock:
            self.sock.sendall(bytes([header]) + bytes(enc) + body)

    def _recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("TV closed the connection")
            buf += chunk
        return buf

    def _read_packet(self):
        h = self._recv_exact(1)[0]
        mult, length = 1, 0
        while True:
            d = self._recv_exact(1)[0]
            length += (d & 127) * mult
            mult *= 128
            if not d & 128:
                break
        return h, self._recv_exact(length) if length else b""

    def connect(self):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE   # the TV uses a self-signed certificate
        raw = socket.create_connection((self.host, TV_PORT), timeout=5)
        self.sock = ctx.wrap_socket(raw)
        body = self._str("MQTT") + bytes([4, 0xC2, 0, 30]) + self._str(self.cid) + self._str(MQTT_USER) + self._str(MQTT_PASS)
        self._send(0x10, body)
        h, b = self._read_packet()
        if h >> 4 != 2 or len(b) < 2 or b[1] != 0:
            raise ConnectionError("TV refused the connection")
        self.sock.settimeout(None)
        self.alive = True
        threading.Thread(target=self._reader, daemon=True).start()
        threading.Thread(target=self._pinger, daemon=True).start()

    def subscribe(self, topic):
        self._send(0x82, struct.pack("!H", 1) + self._str(topic) + b"\x00")

    def publish(self, topic, payload=""):
        self._send(0x30, self._str(topic) + payload.encode())

    def close(self):
        self.alive = False
        try:
            self.sock.close()
        except Exception:
            pass

    def _reader(self):
        try:
            while self.alive:
                h, body = self._read_packet()
                if h >> 4 == 3:
                    tl = struct.unpack("!H", body[:2])[0]
                    topic = body[2:2 + tl].decode(errors="replace")
                    off = 2 + tl + (2 if (h >> 1) & 3 else 0)
                    self.on_message(topic, body[off:].decode(errors="replace"))
        except Exception as e:
            if self.alive:
                self.alive = False
                self.on_close(str(e))

    def _pinger(self):
        while self.alive:
            time.sleep(20)
            try:
                self._send(0xC0, b"")
            except Exception:
                return


# ----------------------------------------------------------------- TV session

class Tv:
    def __init__(self):
        self.cfg = load_config()
        self.mqtt = None
        self.subscribers = set()
        self.sub_lock = threading.Lock()
        self.snapshot = {"status": "connecting", "state": None, "volume": None,
                         "apps": [], "sources": [], "model": self.cfg.get("model"), "pairing": False}
        threading.Thread(target=self._keep_connected, daemon=True).start()

    # events ---------------------------------------------------------
    def emit(self, kind, data):
        self.snapshot[kind] = data
        msg = json.dumps({"type": kind, "data": data})
        with self.sub_lock:
            for q in list(self.subscribers):
                try:
                    q.put_nowait(msg)
                except queue.Full:
                    pass

    def subscribe(self):
        q = queue.Queue(maxsize=200)
        with self.sub_lock:
            self.subscribers.add(q)
        return q

    def unsubscribe(self, q):
        with self.sub_lock:
            self.subscribers.discard(q)

    # connection -----------------------------------------------------
    def _keep_connected(self):
        while True:
            if self.mqtt is None or not self.mqtt.alive:
                # Once we know the TV is off, keep showing that while retrying quietly.
                if self.snapshot.get("status") != "offline":
                    self.emit("status", "connecting")
                host = self.cfg.get("ip") or self.scan()
                if host and self._try(host):
                    pass
                else:
                    found = self.scan() if host else None
                    if found and found != host and self._try(found):
                        pass
                    else:
                        self.emit("status", "offline")
                        time.sleep(6)
                        continue
            time.sleep(2)

    def _try(self, host):
        m = Mqtt(host, self.cfg["cid"], self._on_message, self._on_close)
        try:
            m.connect()
        except Exception:
            return False
        self.mqtt = m
        if self.cfg.get("ip") != host:
            self.cfg["ip"] = host
            save_config(self.cfg)
        m.subscribe("/remoteapp/mobile/#")
        self.emit("status", "connected")
        self.refresh()
        return True

    def _on_close(self, why):
        self.emit("status", "offline")

    def scan(self):
        prefix = None
        if self.cfg.get("ip"):
            prefix = self.cfg["ip"].rsplit(".", 1)[0] + "."
        else:
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.connect(("10.255.255.255", 1))
                prefix = s.getsockname()[0].rsplit(".", 1)[0] + "."
                s.close()
            except Exception:
                return None

        def probe(i):
            try:
                with socket.create_connection((prefix + str(i), TV_PORT), timeout=0.4):
                    return prefix + str(i)
            except Exception:
                return None

        with ThreadPoolExecutor(64) as pool:
            for hit in pool.map(probe, range(1, 255)):
                if hit:
                    return hit
        return None

    # commands -------------------------------------------------------
    def _pub(self, service, action, payload=""):
        if not self.mqtt or not self.mqtt.alive:
            raise ConnectionError("Not connected to the TV")
        self.mqtt.publish(f"/remoteapp/tv/{service}/{self.cfg['cid']}/actions/{action}", payload)

    def refresh(self):
        for svc, act in (("ui_service", "gettvstate"), ("ui_service", "capability"),
                         ("ui_service", "sourcelist"), ("ui_service", "applist"),
                         ("platform_service", "getvolume")):
            self._pub(svc, act)

    def key(self, k):
        self._pub("remote_service", "sendkey", k)

    def text(self, code):
        self._pub("remote_service", "input", code)

    def volume(self, v):
        self._pub("platform_service", "changevolume", str(int(v)))

    def source(self, src):
        self._pub("ui_service", "changesource", json.dumps({"sourceid": src.get("sourceid"), "sourcename": src.get("sourcename")}))

    def app(self, a):
        self._pub("ui_service", "launchapp", json.dumps({
            "appId": a.get("appId", ""), "name": a.get("name", ""), "url": a.get("url", ""),
            "urlType": a.get("urlType", 37), "storeType": a.get("storeType", 0)}))

    def pair(self, code):
        self._pub("ui_service", "authenticationcode", json.dumps({"authNum": str(code)}))

    def wake(self):
        mac = self.cfg.get("mac")
        if not mac:
            raise ValueError("Add the TV's MAC address in settings first")
        packet = b"\xff" * 6 + bytes.fromhex(mac.replace(":", "").replace("-", "")) * 16
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        targets = ["255.255.255.255"]
        if self.cfg.get("ip"):
            targets.append(self.cfg["ip"].rsplit(".", 1)[0] + ".255")
        for _ in range(3):
            for t in targets:
                s.sendto(packet, (t, 9))
        s.close()

    def settings(self, ip=None, mac=None):
        if ip is not None:
            self.cfg["ip"] = ip.strip()
        if mac is not None:
            self.cfg["mac"] = mac.strip().upper()
        save_config(self.cfg)
        if self.mqtt:
            self.mqtt.close()

    # incoming -------------------------------------------------------
    def _on_message(self, topic, payload):
        try:
            if topic.endswith("/data/authentication"):
                self.emit("pairing", True)
                self._pub("ui_service", "vidaa_app_connect",
                          json.dumps({"app_version": 2, "connect_result": 0, "device_type": "Mobile App"}))
            elif topic.endswith("/data/authenticationcode"):
                ok = json.loads(payload).get("result") == 1
                self.emit("pairing", False if ok else "retry")
                if ok:
                    self.refresh()
            elif topic.endswith("/ui_service/state") or topic.endswith("/ui_service/data/state"):
                self.emit("state", json.loads(payload))
            elif topic.endswith("/data/capability"):
                o = json.loads(payload)
                model = (o.get("devicemsg") or "").split("_")[0]
                if model:
                    name = ("Hisense " if o.get("vendorBrand") == "his" else "") + model
                    self.cfg["model"] = name
                    save_config(self.cfg)
                    self.emit("model", name)
            elif topic.endswith("/data/sourcelist"):
                self.emit("sources", json.loads(payload))
            elif topic.endswith("/data/applist"):
                self.emit("apps", json.loads(payload))
            elif "volumechange" in topic or topic.endswith("/data/volume") or topic.endswith("/ui_service/volume"):
                v = json.loads(payload).get("volume_value")
                if v is not None:
                    self.emit("volume", v)
            elif topic.endswith("/actions/tvsleep"):
                self.emit("state", {"statetype": "fake_sleep_0"})
        except Exception:
            pass


TV = None


# ----------------------------------------------------------------- HTTP API

MIME = {".html": "text/html; charset=utf-8", ".js": "text/javascript", ".css": "text/css",
        ".svg": "image/svg+xml", ".png": "image/png", ".json": "application/json",
        ".webmanifest": "application/manifest+json"}


class Handler(BaseHTTPRequestHandler):
    server_version = "CrimsonBridge/1.0"

    def log_message(self, fmt, *args):
        pass

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self._cors()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        if not TOKEN:
            return True
        auth = self.headers.get("Authorization", "")
        q = urlparse(self.path).query
        return auth == "Bearer " + TOKEN or ("token=" + TOKEN) in q.split("&")

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self):
        path = urlparse(self.path).path
        if path.startswith("/api/"):
            if not self._authorized():
                return self._json(401, {"error": "Wrong or missing token"})
            if path == "/api/events":
                return self._events()
            if path == "/api/snapshot":
                return self._json(200, TV.snapshot)
            return self._json(404, {"error": "unknown endpoint"})
        self._static(path)

    def do_POST(self):
        path = urlparse(self.path).path
        if not self._authorized():
            return self._json(401, {"error": "Wrong or missing token"})
        try:
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length) or b"{}")
            routes = {
                "/api/key": lambda: TV.key(body["key"]),
                "/api/text": lambda: TV.text(body["code"]),
                "/api/volume": lambda: TV.volume(body["value"]),
                "/api/source": lambda: TV.source(body),
                "/api/app": lambda: TV.app(body),
                "/api/pair": lambda: TV.pair(body["code"]),
                "/api/wake": TV.wake,
                "/api/refresh": TV.refresh,
                "/api/settings": lambda: TV.settings(body.get("ip"), body.get("mac")),
            }
            if path not in routes:
                return self._json(404, {"error": "unknown endpoint"})
            routes[path]()
            self._json(200, {"ok": True})
        except Exception as e:
            self._json(409, {"error": str(e)})

    def _events(self):
        self.send_response(200)
        self._cors()
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        q = TV.subscribe()
        try:
            self.wfile.write(("data: " + json.dumps({"type": "snapshot", "data": TV.snapshot}) + "\n\n").encode())
            self.wfile.flush()
            while True:
                try:
                    msg = q.get(timeout=15)
                    self.wfile.write(("data: " + msg + "\n\n").encode())
                except queue.Empty:
                    self.wfile.write(b": ping\n\n")
                self.wfile.flush()
        except Exception:
            pass
        finally:
            TV.unsubscribe(q)

    def _static(self, path):
        if path in ("", "/"):
            path = "/index.html"
        full = os.path.abspath(os.path.join(WEB_DIR, path.lstrip("/")))
        if not full.startswith(WEB_DIR) or not os.path.isfile(full):
            self.send_response(404)
            self.end_headers()
            return
        with open(full, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", MIME.get(os.path.splitext(full)[1], "application/octet-stream"))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)


def main():
    global TV
    TV = Tv()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        lan = s.getsockname()[0]
        s.close()
    except Exception:
        lan = "localhost"
    print(f"Crimson Bridge running. Open http://{lan}:{PORT} on your phone.")
    if TOKEN:
        print("Token protection is ON.")
    server.serve_forever()


if __name__ == "__main__":
    main()
