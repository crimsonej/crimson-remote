import Foundation
import Network

/// TV brands Crimson can control. Hisense uses TVController's MQTT code;
/// the drivers below add Roku, Samsung and LG.
enum TVBrand: String, Codable {
    case hisense, roku, samsung, lg
    var label: String {
        switch self {
        case .hisense: return "Hisense"
        case .roku: return "Roku"
        case .samsung: return "Samsung"
        case .lg: return "LG"
        }
    }
}

/// What a driver reports back. Every callback is delivered on the main thread.
struct DriverEvents {
    var connected: () -> Void = {}
    var disconnected: () -> Void = {}
    var prompt: (String) -> Void = { _ in }
    var state: (String, String) -> Void = { _, _ in }
    var volume: (Int) -> Void = { _ in }
    var apps: ([TVApp]) -> Void = { _ in }
    var sources: ([TVSource]) -> Void = { _ in }
    var model: (String) -> Void = { _ in }
    /// The TV's MAC address, learned while connecting, so it can be turned on later.
    var mac: (String) -> Void = { _ in }
}

/// Base class; keys use the app's common names (KEY_UP, KEY_OK …), text uses Lit_ codes.
class TVDriver {
    let ip: String
    /// The saved TV's id; pairing tokens are kept under it so they survive address changes.
    let id: String
    var events: DriverEvents
    var isConnected: Bool { false }
    var canSetVolume: Bool { false }
    /// Features this particular TV adds to its brand's set (learned while connecting).
    var extraFeatures: Set<Feature> = []
    let defaults = UserDefaults.standard

    init(ip: String, id: String, events: DriverEvents) {
        self.ip = ip
        self.id = id
        self.events = events
    }

    func connect() async -> Bool { false }
    func close() {}
    func key(_ key: String) {}
    func text(_ code: String) {}
    func setVolume(_ v: Int) {}
    func launch(_ app: TVApp) {}
    func source(_ s: TVSource) {}
    func refresh() {}

    static func make(_ brand: TVBrand, ip: String, id: String, events: DriverEvents) -> TVDriver? {
        switch brand {
        case .roku: return RokuDriver(ip: ip, id: id, events: events)
        case .samsung: return SamsungDriver(ip: ip, id: id, events: events)
        case .lg: return LGDriver(ip: ip, id: id, events: events)
        case .hisense: return nil
        }
    }

    func main(_ f: @escaping () -> Void) { DispatchQueue.main.async(execute: f) }

    /// The single character in a Lit_ code, or nil for a special code.
    static func litChar(_ code: String) -> String? {
        let v = String(code.dropFirst(4))
        if v == "SPACE" { return " " }
        return v.count == 1 ? v : nil
    }

    // MARK: discovery

    static let ports: [UInt16] = [36669, 8060, 8001, 8002, 3000, 3001]

    static func tcpOpen(_ host: String, _ port: UInt16, timeout: Double = 0.9) async -> Bool {
        await withCheckedContinuation { cont in
            let q = DispatchQueue(label: "probe.\(host).\(port)")
            let conn = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
            var done = false
            let finish: (Bool) -> Void = { ok in
                q.async {
                    if done { return }
                    done = true
                    conn.cancel()
                    cont.resume(returning: ok)
                }
            }
            conn.stateUpdateHandler = { s in
                switch s {
                case .ready: finish(true)
                case .failed, .waiting: finish(false)
                default: break
                }
            }
            conn.start(queue: q)
            q.asyncAfter(deadline: .now() + timeout) { finish(false) }
        }
    }

    static func fetch(_ url: String, timeout: Double = 2.5) async -> String? {
        guard let u = URL(string: url) else { return nil }
        var r = URLRequest(url: u)
        r.timeoutInterval = timeout
        guard let (d, resp) = try? await URLSession.shared.data(for: r),
              (resp as? HTTPURLResponse).map({ $0.statusCode < 400 }) ?? true else { return nil }
        return String(decoding: d, as: UTF8.self)
    }

    static func tag(_ xml: String?, _ t: String) -> String? {
        guard let xml, let a = xml.range(of: "<\(t)>"), let b = xml.range(of: "</\(t)>", range: a.upperBound..<xml.endIndex) else { return nil }
        let v = xml[a.upperBound..<b.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines)
        return v.isEmpty ? nil : v
    }

    /// "key=value" lines, as in Hisense's modelDescription.
    static func field(_ text: String?, _ key: String) -> String? {
        guard let text else { return nil }
        for line in text.components(separatedBy: .newlines) {
            let l = line.trimmingCharacters(in: .whitespaces)
            if l.hasPrefix(key + "=") { return String(l.dropFirst(key.count + 1)) }
        }
        return nil
    }

    /// Brand, name, model and MAC of a host, or nil if it isn't a TV Crimson knows.
    static func identify(_ host: String) async -> (TVBrand, String?, String?, String?)? {
        if await tcpOpen(host, 36669) {
            let xml = await fetch("http://\(host):38400/MediaServer/rendererdevicedesc.xml")
            let desc = tag(xml, "modelDescription")
            let model = field(desc, "model_name").map { String($0.split(separator: "_").first ?? "") }
            return (.hisense, tag(xml, "friendlyName"), model, field(desc, "mac"))
        }
        if await tcpOpen(host, 8060), let xml = await fetch("http://\(host):8060/query/device-info"), xml.contains("<device-info") {
            let mac = tag(xml, "network-type") == "ethernet" ? tag(xml, "ethernet-mac") : tag(xml, "wifi-mac")
            return (.roku, tag(xml, "user-device-name") ?? tag(xml, "friendly-device-name") ?? "Roku", tag(xml, "model-name"), mac)
        }
        let samsungPort = await tcpOpen(host, 8001)
        let samsungSecure = samsungPort ? true : await tcpOpen(host, 8002)
        if samsungPort || samsungSecure {
            if let js = await fetch("http://\(host):8001/api/v2/"),
               let o = try? JSONSerialization.jsonObject(with: Data(js.utf8)) as? [String: Any],
               let d = o["device"] as? [String: Any] {
                let name = ((d["name"] as? String) ?? "Samsung TV").replacingOccurrences(of: "[TV] ", with: "")
                return (.samsung, name, d["modelName"] as? String, d["wifiMac"] as? String)
            }
        }
        let lgPort = await tcpOpen(host, 3000)
        let lgSecure = lgPort ? true : await tcpOpen(host, 3001)
        if lgPort || lgSecure { return (.lg, "LG TV", nil, nil) }
        return nil
    }
}

// MARK: - WebSocket that accepts the TV's self-signed certificate

final class TVSocket: NSObject, URLSessionWebSocketDelegate {
    private var session: URLSession!
    private var task: URLSessionWebSocketTask?
    private var opened: CheckedContinuation<Bool, Never>?
    var onText: (String) -> Void = { _ in }
    var onClose: () -> Void = {}
    private(set) var isOpen = false

    override init() {
        super.init()
        session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    }

    func connect(_ url: URL) async -> Bool {
        await withCheckedContinuation { c in
            opened = c
            task = session.webSocketTask(with: url)
            task?.resume()
            DispatchQueue.global().asyncAfter(deadline: .now() + 6) { [weak self] in self?.finishOpen(false) }
        }
    }

    private func finishOpen(_ ok: Bool) {
        guard let c = opened else { return }
        opened = nil
        isOpen = ok
        c.resume(returning: ok)
        if ok { receive() } else { task?.cancel() }
    }

    private func receive() {
        task?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let m):
                if case .string(let s) = m { self.onText(s) }
                if case .data(let d) = m { self.onText(String(decoding: d, as: UTF8.self)) }
                self.receive()
            case .failure:
                if self.isOpen { self.isOpen = false; self.onClose() }
            }
        }
    }

    func send(_ text: String) { task?.send(.string(text)) { _ in } }

    func close() {
        isOpen = false
        task?.cancel(with: .goingAway, reason: nil)
    }

    func urlSession(_ s: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol p: String?) { finishOpen(true) }

    func urlSession(_ s: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith code: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        if isOpen { isOpen = false; onClose() }
    }

    func urlSession(_ s: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if opened != nil { finishOpen(false) } else if isOpen { isOpen = false; onClose() }
    }

    func urlSession(_ s: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        if let trust = challenge.protectionSpace.serverTrust {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }

    func urlSession(_ s: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        urlSession(s, didReceive: challenge, completionHandler: completionHandler)
    }
}

func jsonString(_ o: Any) -> String {
    guard let d = try? JSONSerialization.data(withJSONObject: o) else { return "{}" }
    return String(decoding: d, as: UTF8.self)
}

func jsonObject(_ s: String) -> [String: Any]? {
    (try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? [String: Any]
}

// MARK: - Roku

/// Roku and Roku TVs: External Control Protocol over HTTP on port 8060, no pairing.
final class RokuDriver: TVDriver {
    private var up = false
    private var poll: Task<Void, Never>?
    private var base: String { "http://\(ip):8060" }
    override var isConnected: Bool { up }

    static let keys: [String: String] = [
        "KEY_POWER": "Power", "KEY_UP": "Up", "KEY_DOWN": "Down", "KEY_LEFT": "Left", "KEY_RIGHT": "Right",
        "KEY_OK": "Select", "KEY_RETURNS": "Back", "KEY_HOME": "Home", "KEY_MENU": "Info", "KEY_EXIT": "Home",
        "KEY_INFO": "Info", "KEY_VOLUMEUP": "VolumeUp", "KEY_VOLUMEDOWN": "VolumeDown", "KEY_MUTE": "VolumeMute",
        "KEY_CHANNELUP": "ChannelUp", "KEY_CHANNELDOWN": "ChannelDown", "KEY_PLAY": "Play", "KEY_PAUSE": "Play",
        "KEY_BACK": "Rev", "KEY_FORWARDS": "Fwd", "KEY_SUBTITLE": "InstantReplay",
    ]

    override func connect() async -> Bool {
        guard let info = await TVDriver.fetch(base + "/query/device-info", timeout: 3) else { return false }
        up = true
        let model = TVDriver.tag(info, "model-name") ?? ""
        let name = TVDriver.tag(info, "user-device-name")
        let mac = TVDriver.tag(info, "network-type") == "ethernet" ? TVDriver.tag(info, "ethernet-mac") : TVDriver.tag(info, "wifi-mac")
        if TVDriver.tag(info, "is-tv") == "true" { extraFeatures = [.channels, .inputList] }
        main {
            self.events.model("Roku \(model)")
            if let mac { self.events.mac(mac) }
            self.events.connected()
            if let name { self.events.state(name, "Roku") }
            if TVDriver.tag(info, "is-tv") == "true" {
                self.events.sources([("InputTuner", "TV"), ("InputHDMI1", "HDMI 1"), ("InputHDMI2", "HDMI 2"),
                                     ("InputHDMI3", "HDMI 3"), ("InputHDMI4", "HDMI 4"), ("InputAV1", "AV")]
                    .map { TVSource(sourceId: $0.0, sourceName: $0.1, displayName: $0.1, hasSignal: false) })
            }
        }
        refresh()
        poll = Task { [weak self] in
            while let self, self.up, !Task.isCancelled {
                await self.pollActive()
                try? await Task.sleep(nanoseconds: 5_000_000_000)
            }
        }
        return true
    }

    private func pollActive() async {
        guard let xml = await TVDriver.fetch(base + "/query/active-app") else {
            if up { up = false; main { self.events.disconnected() } }
            return
        }
        guard let a = xml.range(of: "<app"), let s = xml.range(of: ">", range: a.upperBound..<xml.endIndex),
              let e = xml.range(of: "</app>", range: s.upperBound..<xml.endIndex) else { return }
        let app = xml[s.upperBound..<e.lowerBound].trimmingCharacters(in: .whitespaces)
        main { self.events.state(app.isEmpty || app == "Roku" ? "Home screen" : app, "Roku") }
    }

    override func refresh() {
        Task {
            guard let xml = await TVDriver.fetch(base + "/query/apps", timeout: 3) else { return }
            var apps: [TVApp] = []
            var rest = xml[...]
            while let a = rest.range(of: "<app "), let idA = rest.range(of: "id=\"", range: a.upperBound..<rest.endIndex),
                  let idB = rest.range(of: "\"", range: idA.upperBound..<rest.endIndex),
                  let s = rest.range(of: ">", range: idB.upperBound..<rest.endIndex),
                  let e = rest.range(of: "</app>", range: s.upperBound..<rest.endIndex) {
                let id = String(rest[idA.upperBound..<idB.lowerBound])
                let name = rest[s.upperBound..<e.lowerBound].replacingOccurrences(of: "&amp;", with: "&")
                var app = TVApp(name: name, url: "", appId: id, urlType: 0, storeType: 0)
                app.iconURL = "\(base)/query/icon/\(id)"
                apps.append(app)
                rest = rest[e.upperBound...]
            }
            main { self.events.apps(apps) }
        }
    }

    private func press(_ k: String) {
        guard let u = URL(string: "\(base)/keypress/\(k)") else { return }
        var r = URLRequest(url: u)
        r.httpMethod = "POST"
        URLSession.shared.dataTask(with: r).resume()
    }

    override func close() { up = false; poll?.cancel() }

    override func key(_ key: String) {
        if let k = Self.keys[key] { press(k); return }
        if key.count == 5, key.hasPrefix("KEY_"), let d = key.last, d.isNumber { press("Lit_\(d)") }
    }

    override func text(_ code: String) {
        if code == "Lit_BACKSPACE" { press("Backspace"); return }
        if code == "Lit_ENTER" { press("Enter"); return }
        guard let ch = TVDriver.litChar(code),
              let enc = ch.addingPercentEncoding(withAllowedCharacters: .alphanumerics) else { return }
        press("Lit_\(enc)")
    }

    override func launch(_ app: TVApp) {
        guard let u = URL(string: "\(base)/launch/\(app.appId)") else { return }
        var r = URLRequest(url: u)
        r.httpMethod = "POST"
        URLSession.shared.dataTask(with: r).resume()
    }

    override func source(_ s: TVSource) { press(s.sourceId) }
}

// MARK: - Samsung

/// Samsung Tizen TVs (2016+): WebSocket remote API. The TV asks the user to Allow once.
final class SamsungDriver: TVDriver {
    private let ws = TVSocket()
    override var isConnected: Bool { ws.isOpen }
    private var tokenKey: String { "samsung_token_\(id)" }

    static let keys: [String: String] = [
        "KEY_OK": "KEY_ENTER", "KEY_RETURNS": "KEY_RETURN", "KEY_VOLUMEUP": "KEY_VOLUP", "KEY_VOLUMEDOWN": "KEY_VOLDOWN",
        "KEY_CHANNELUP": "KEY_CHUP", "KEY_CHANNELDOWN": "KEY_CHDOWN", "KEY_BACK": "KEY_REWIND", "KEY_FORWARDS": "KEY_FF",
        "KEY_SUBTITLE": "KEY_SUB_TITLE", "KEY_BLUE": "KEY_CYAN",
    ]

    override func connect() async -> Bool {
        let name = Data("Crimson Remote".utf8).base64EncodedString()
        let token = defaults.string(forKey: tokenKey) ?? ""
        let urls = ["wss://\(ip):8002/api/v2/channels/samsung.remote.control?name=\(name)" + (token.isEmpty ? "" : "&token=\(token)"),
                    "ws://\(ip):8001/api/v2/channels/samsung.remote.control?name=\(name)"]
        ws.onText = { [weak self] t in self?.handle(t) }
        ws.onClose = { [weak self] in self?.main { self?.events.disconnected() } }
        for u in urls {
            guard let url = URL(string: u), await ws.connect(url) else { continue }
            if token.isEmpty { main { self.events.prompt("Tap Allow on the TV to let Crimson control it") } }
            if let js = await TVDriver.fetch("http://\(ip):8001/api/v2/"), let d = jsonObject(js)?["device"] as? [String: Any] {
                let model = d["modelName"] as? String ?? ""
                main { self.events.model("Samsung \(model)") }
                if let mac = d["wifiMac"] as? String { main { self.events.mac(mac) } }
            }
            return true
        }
        return false
    }

    private func handle(_ text: String) {
        guard let o = jsonObject(text), let ev = o["event"] as? String else { return }
        switch ev {
        case "ms.channel.connect":
            if let t = (o["data"] as? [String: Any])?["token"] as? String { defaults.set(t, forKey: tokenKey) }
            main { self.events.connected(); self.events.state("Samsung TV", "Connected") }
            refresh()
        case "ms.channel.unauthorized":
            main { self.events.prompt("The TV said no. Allow Crimson in the TV's device connection settings, then retry.") }
        case "ed.installedApp.get":
            let list = ((o["data"] as? [String: Any])?["data"] as? [[String: Any]]) ?? []
            let apps = list.map { a -> TVApp in
                TVApp(name: a["name"] as? String ?? "", url: "", appId: a["appId"] as? String ?? "",
                      urlType: a["app_type"] as? Int ?? 2, storeType: 0)
            }
            main { self.events.apps(apps) }
        default: break
        }
    }

    override func refresh() {
        ws.send(jsonString(["method": "ms.channel.emit", "params": ["event": "ed.installedApp.get", "to": "host"]]))
    }

    override func close() { ws.close() }

    override func key(_ key: String) {
        guard key != "KEY_LEFTMOUSEKEYS" else { return }
        ws.send(jsonString(["method": "ms.remote.control", "params": [
            "Cmd": "Click", "DataOfCmd": Self.keys[key] ?? key, "Option": "false", "TypeOfRemote": "SendRemoteKey"]]))
    }

    override func text(_ code: String) {
        if code == "Lit_BACKSPACE" { key("KEY_BACKSPACE"); return }
        if code == "Lit_ENTER" {
            ws.send(jsonString(["method": "ms.remote.control", "params": ["TypeOfRemote": "SendInputEnd"]]))
            return
        }
        guard let ch = TVDriver.litChar(code) else { return }
        ws.send(jsonString(["method": "ms.remote.control", "params": [
            "Cmd": Data(ch.utf8).base64EncodedString(), "DataOfCmd": "base64", "TypeOfRemote": "SendInputString"]]))
    }

    override func launch(_ app: TVApp) {
        ws.send(jsonString(["method": "ms.channel.emit", "params": [
            "event": "ed.apps.launch", "to": "host",
            "data": ["appId": app.appId, "action_type": app.urlType == 2 ? "DEEP_LINK" : "NATIVE_LAUNCH"]]]))
    }

    override func source(_ s: TVSource) { key("KEY_SOURCE") }
}

// MARK: - LG

/// LG webOS TVs: SSAP over WebSocket. The TV asks the user to Accept once.
final class LGDriver: TVDriver {
    private let ws = TVSocket()
    private let pointer = TVSocket()
    private var counter = 1
    private var tags: [String: String] = [:]
    private let lock = NSLock()
    override var isConnected: Bool { ws.isOpen }
    override var canSetVolume: Bool { true }
    private var keyName: String { "lg_key_\(id)" }

    static let buttons: [String: String] = {
        var m = ["KEY_UP": "UP", "KEY_DOWN": "DOWN", "KEY_LEFT": "LEFT", "KEY_RIGHT": "RIGHT", "KEY_OK": "ENTER",
                 "KEY_RETURNS": "BACK", "KEY_HOME": "HOME", "KEY_MENU": "MENU", "KEY_EXIT": "EXIT", "KEY_INFO": "INFO",
                 "KEY_RED": "RED", "KEY_GREEN": "GREEN", "KEY_YELLOW": "YELLOW", "KEY_BLUE": "BLUE", "KEY_SUBTITLE": "CC",
                 "KEY_CHANNELUP": "CHANNELUP", "KEY_CHANNELDOWN": "CHANNELDOWN", "KEY_MUTE": "MUTE"]
        for i in 0...9 { m["KEY_\(i)"] = "\(i)" }
        return m
    }()

    override func connect() async -> Bool {
        ws.onText = { [weak self] t in self?.handle(t) }
        ws.onClose = { [weak self] in self?.pointer.close(); self?.main { self?.events.disconnected() } }
        for u in ["wss://\(ip):3001/", "ws://\(ip):3000/"] {
            guard let url = URL(string: u), await ws.connect(url) else { continue }
            register()
            return true
        }
        return false
    }

    private func register() {
        guard let file = Bundle.main.url(forResource: "lg_manifest", withExtension: "json"),
              let data = try? Data(contentsOf: file),
              var payload = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        if let key = defaults.string(forKey: keyName) { payload["client-key"] = key }
        else { main { self.events.prompt("Tap Accept on the TV to let Crimson control it") } }
        ws.send(jsonString(["type": "register", "id": "register_0", "payload": payload]))
    }

    @discardableResult
    private func request(_ uri: String, _ payload: [String: Any]? = nil, tag: String? = nil, subscribe: Bool = false) -> String {
        lock.lock()
        let id = "\(tag ?? "req")_\(counter)"
        counter += 1
        if let tag { tags[id] = tag }
        lock.unlock()
        var msg: [String: Any] = ["type": subscribe ? "subscribe" : "request", "id": id, "uri": uri]
        if let payload { msg["payload"] = payload }
        ws.send(jsonString(msg))
        return id
    }

    private func handle(_ text: String) {
        guard let o = jsonObject(text), let id = o["id"] as? String else { return }
        let type = o["type"] as? String ?? ""
        let p = o["payload"] as? [String: Any]
        if id == "register_0" {
            if type == "registered" {
                if let key = p?["client-key"] as? String { defaults.set(key, forKey: keyName) }
                main { self.events.connected() }
                afterRegister()
            } else if type == "error" {
                main { self.events.prompt("The TV didn't accept Crimson. Try again and choose Accept on the TV.") }
            }
            return
        }
        lock.lock(); let tag = tags[id]; lock.unlock()
        guard let p, let tag else { return }
        switch tag {
        case "apps":
            let list = (p["apps"] as? [[String: Any]]) ?? []
            let apps = list.filter { ($0["visible"] as? Bool) ?? true }.map { a -> TVApp in
                var app = TVApp(name: a["title"] as? String ?? "", url: "", appId: a["id"] as? String ?? "", urlType: 0, storeType: 0)
                app.iconURL = a["icon"] as? String
                return app
            }
            main { self.events.apps(apps) }
        case "inputs":
            let list = (p["devices"] as? [[String: Any]]) ?? []
            let src = list.map { d in
                TVSource(sourceId: d["id"] as? String ?? "", sourceName: d["id"] as? String ?? "",
                         displayName: d["label"] as? String ?? "", hasSignal: d["connected"] as? Bool ?? false)
            }
            main { self.events.sources(src) }
        case "volume":
            let v = (p["volume"] as? Int) ?? ((p["volumeStatus"] as? [String: Any])?["volume"] as? Int)
            if let v { main { self.events.volume(v) } }
        case "fg":
            let app = p["appId"] as? String ?? ""
            let name = app.split(separator: ".").last.map { $0.prefix(1).uppercased() + $0.dropFirst() } ?? "LG TV"
            main { self.events.state(app.isEmpty ? "LG TV" : name, "LG webOS") }
        case "info":
            let model = p["modelName"] as? String ?? ""
            main { self.events.model("LG \(model)") }
        case "net":
            let wired = p["wiredInfo"] as? [String: Any], wifi = p["wifiInfo"] as? [String: Any]
            let mac = (wired?["state"] as? String) == "connected" ? wired?["macAddress"] as? String : wifi?["macAddress"] as? String
            if let mac, !mac.isEmpty { main { self.events.mac(mac) } }
        case "pointer":
            if let path = p["socketPath"] as? String, let url = URL(string: path) {
                Task { _ = await self.pointer.connect(url) }
            }
        default: break
        }
    }

    private func afterRegister() {
        request("ssap://com.webos.service.networkinput/getPointerInputSocket", tag: "pointer")
        request("ssap://system/getSystemInfo", tag: "info")
        request("ssap://com.webos.service.connectionmanager/getinfo", tag: "net")
        request("ssap://audio/getVolume", tag: "volume", subscribe: true)
        request("ssap://com.webos.applicationManager/getForegroundAppInfo", tag: "fg", subscribe: true)
        refresh()
    }

    override func refresh() {
        request("ssap://com.webos.applicationManager/listApps", tag: "apps")
        request("ssap://tv/getExternalInputList", tag: "inputs")
    }

    override func close() { pointer.close(); ws.close() }

    private func button(_ name: String) { pointer.send("type:button\nname:\(name)\n\n") }

    override func key(_ key: String) {
        switch key {
        case "KEY_VOLUMEUP": request("ssap://audio/volumeUp")
        case "KEY_VOLUMEDOWN": request("ssap://audio/volumeDown")
        case "KEY_POWER": request("ssap://system/turnOff")
        case "KEY_PLAY": request("ssap://media.controls/play")
        case "KEY_PAUSE": request("ssap://media.controls/pause")
        case "KEY_BACK": request("ssap://media.controls/rewind")
        case "KEY_FORWARDS": request("ssap://media.controls/fastForward")
        default: if let b = Self.buttons[key] { button(b) }
        }
    }

    override func text(_ code: String) {
        if code == "Lit_BACKSPACE" { request("ssap://com.webos.service.ime/deleteCharacters", ["count": 1]); return }
        if code == "Lit_ENTER" { request("ssap://com.webos.service.ime/sendEnterKey"); return }
        if let ch = TVDriver.litChar(code) { request("ssap://com.webos.service.ime/insertText", ["text": ch, "replace": 0]) }
    }

    override func setVolume(_ v: Int) { request("ssap://audio/setVolume", ["volume": v]) }
    /// Picture off, sound on.
    func screenOff() { request("ssap://com.webos.service.tvpower/power/turnOffScreen", ["standbyMode": "active"]) }
    override func launch(_ app: TVApp) { request("ssap://system.launcher/launch", ["id": app.appId]) }
    override func source(_ s: TVSource) { request("ssap://tv/switchInput", ["inputId": s.sourceId]) }
}
