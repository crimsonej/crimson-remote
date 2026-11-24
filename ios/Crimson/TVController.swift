import Foundation
import Network
import SwiftUI
import UIKit

struct TVApp: Identifiable, Hashable {
    let id = UUID()
    let name: String
    let url: String
    let appId: String
    let urlType: Int
    let storeType: Int
    /// An icon the TV itself serves (Roku, LG), preferred over the site icon.
    var iconURL: String? = nil

    var displayName: String {
        name.trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: #"\s+\d+(\.\d+)*$"#, with: "", options: .regularExpression)
    }

    /// Each service's own site icon stands in for its logo.
    var logoURL: URL? {
        if let iconURL, !iconURL.isEmpty, let u = URL(string: iconURL) { return u }
        let n = displayName.lowercased()
        let known: [(String, String)] = [
            ("youtube", "youtube.com"), ("netflix", "netflix.com"), ("prime", "primevideo.com"),
            ("dstv", "dstv.com"), ("showmax", "showmax.com"), ("accuweather", "accuweather.com"),
            ("mindset", "mindset.africa"), ("appsnow", "vidaa.com"), ("toon goggles", "toongoggles.com"),
            ("yupp", "yupptv.com"), ("spotify", "spotify.com"), ("disney", "disneyplus.com"),
            ("deezer", "deezer.com"), ("facebook", "facebook.com"), ("tiktok", "tiktok.com"),
            ("plex", "plex.tv"), ("pluto", "pluto.tv"),
        ]
        var domain = known.first { n.contains($0.0) }?.1
        if domain == nil, url.hasPrefix("http"), let host = URL(string: url)?.host {
            domain = host.split(separator: ".").suffix(2).joined(separator: ".")
        }
        guard let domain else { return nil }
        return URL(string: "https://www.google.com/s2/favicons?sz=128&domain=\(domain)")
    }
}

/// A TV found on this Wi-Fi.
struct TVDevice: Identifiable, Hashable {
    var id: String { ip }
    let ip: String
    var name: String
    var model: String?
    var brand: TVBrand = .hisense
}

struct TVSource: Identifiable, Hashable {
    var id: String { sourceId }
    let sourceId: String
    let sourceName: String
    let displayName: String
    let hasSignal: Bool
}

@MainActor
final class TVController: ObservableObject {
    enum Status: Equatable { case connecting, searching, connected, offline, pairing }

    @Published var status: Status = .connecting
    @Published var model: String
    @Published var nowTitle = "Connecting…"
    @Published var nowSubtitle = " "
    @Published var volume: Double = 0
    @Published var apps: [TVApp] = TVController.defaultApps
    @Published var sources: [TVSource] = []
    @Published var showPairing = false
    @Published var pairingFailed = false
    @Published var toast: String?
    @Published var devices: [TVDevice] = []
    @Published var scanning = false
    @Published var connectedIP: String?

    /// The connected TV's own name, or its model while the name is unknown.
    var displayName: String {
        if let ip = connectedIP ?? (ip.isEmpty ? nil : ip), let d = devices.first(where: { $0.ip == ip }), !d.name.hasPrefix("TV at") {
            return d.name
        }
        return model
    }

    private var client: MQTTClient?
    /// The non-Hisense TV being controlled (Roku, Samsung, LG), if any.
    private var driver: TVDriver?
    var isLive: Bool { client?.isConnected == true || driver?.isConnected == true }
    @Published var canSetVolume = true
    private let defaults = UserDefaults.standard
    private var lastVolumeSent = Date.distantPast
    private var connectTask: Task<Void, Never>?

    var ip: String {
        get { defaults.string(forKey: "ip") ?? "" }
        set { defaults.set(newValue, forKey: "ip") }
    }
    var mac: String {
        get { defaults.string(forKey: "mac") ?? "" }
        set { defaults.set(newValue.uppercased(), forKey: "mac") }
    }
    private var clientId: String {
        if let id = defaults.string(forKey: "cid") { return id }
        let id = "CrimsonIOS-" + UUID().uuidString.prefix(8).lowercased()
        defaults.set(id, forKey: "cid")
        return id
    }

    init() {
        model = UserDefaults.standard.string(forKey: "model") ?? "Your TV"
    }

    static let defaultApps = [
        TVApp(name: "YouTube", url: "youtube", appId: "3", urlType: 37, storeType: 0),
        TVApp(name: "Netflix", url: "netflix", appId: "1", urlType: 37, storeType: 0),
        TVApp(name: "Prime Video", url: "amazon", appId: "", urlType: 37, storeType: 0),
    ]

    // MARK: - Connection

    /// Connects to the chosen TV, or the first one found, and keeps the device list fresh.
    func connect() {
        connectTask?.cancel()
        connectTask = Task {
            if isLive { return }
            status = .connecting
            if !ip.isEmpty, await attemptAny(ip) {
                Task { await discover() }
                return
            }
            status = .searching
            await discover()
            let preferred = devices.first { $0.ip == ip } ?? devices.first
            if let target = preferred, await attemptAny(target.ip) {
                ip = target.ip
                if devices.count > 1 { show("Found \(devices.count) TVs · connected to \(target.name)") }
                return
            }
            status = .offline
            nowTitle = "TV is off"
            nowSubtitle = mac.isEmpty ? "Turn it on, and keep the iPhone on the same Wi-Fi" : "Tap power to wake it"
        }
    }

    /// Scans the Wi-Fi for every TV and looks up its name.
    func discover() async {
        scanning = true
        defer { scanning = false }
        let hosts = await Self.scanAll(prefix: prefix())
        var found: [TVDevice] = []
        for host in hosts {
            guard let info = await TVDriver.identify(host) else { continue }
            found.append(TVDevice(ip: host, name: info.1 ?? "TV at \(host)", model: info.2, brand: info.0))
            defaults.set(info.0.rawValue, forKey: "brand_\(host)")
        }
        if let current = connectedIP, !found.contains(where: { $0.ip == current }) {
            found.insert(TVDevice(ip: current, name: model, model: nil, brand: brand(of: current)), at: 0)
        }
        withAnimation(.easeOut(duration: 0.25)) { devices = found.sorted { $0.ip.compare($1.ip, options: .numeric) == .orderedAscending } }
    }

    /// Switches to another TV from the list.
    func select(_ device: TVDevice) {
        Haptics.tap()
        guard device.ip != connectedIP else { return }
        ip = device.ip
        disconnect()
        connectedIP = nil
        nowTitle = "Connecting…"
        nowSubtitle = device.name
        connect()
    }

    func disconnect() {
        client?.close()
        client = nil
        driver?.close()
        driver = nil
    }

    func brand(of host: String) -> TVBrand {
        if let d = devices.first(where: { $0.ip == host }) { return d.brand }
        return TVBrand(rawValue: defaults.string(forKey: "brand_\(host)") ?? "") ?? .hisense
    }

    private func attemptAny(_ host: String) async -> Bool {
        let b = brand(of: host)
        return b == .hisense ? await attempt(host) : await attemptDriver(host, b)
    }

    private func attemptDriver(_ host: String, _ brand: TVBrand) async -> Bool {
        var ev = DriverEvents()
        ev.connected = { [weak self] in self?.status = .connected; self?.connectedIP = host }
        ev.disconnected = { [weak self] in
            guard let self, self.driver?.ip == host else { return }
            self.driver = nil
            self.connectedIP = nil
            self.status = .offline
        }
        ev.prompt = { [weak self] m in
            self?.status = .pairing
            self?.nowTitle = "Check your TV"
            self?.nowSubtitle = m
            self?.show(m)
        }
        ev.state = { [weak self] t, s in self?.nowTitle = t; self?.nowSubtitle = s }
        ev.volume = { [weak self] v in withAnimation(.easeOut(duration: 0.25)) { self?.volume = Double(v) } }
        ev.apps = { [weak self] a in if !a.isEmpty { self?.apps = Array(a.prefix(16)) } }
        ev.sources = { [weak self] s in self?.sources = s }
        ev.model = { [weak self] m in
            self?.model = m.trimmingCharacters(in: .whitespaces)
            self?.defaults.set(self?.model, forKey: "model")
        }
        guard let d = TVDriver.make(brand, ip: host, events: ev) else { return false }
        client?.close()
        client = nil
        driver?.close()
        driver = d
        guard await d.connect() else { driver = nil; return false }
        connectedIP = host
        if status != .pairing { status = .connected }
        canSetVolume = d.canSetVolume
        return true
    }

    private func attempt(_ host: String) async -> Bool {
        client?.close()
        let c = MQTTClient(host: host, clientId: clientId)
        let ok: Bool = await withCheckedContinuation { cont in
            var resumed = false
            let resume: (Bool) -> Void = { value in
                DispatchQueue.main.async {
                    if !resumed { resumed = true; cont.resume(returning: value) }
                }
            }
            c.onConnected = { resume(true) }
            c.onClosed = { [weak self] in
                resume(false)
                Task { @MainActor in self?.connectionLost(c) }
            }
            c.onMessage = { [weak self] topic, payload in
                Task { @MainActor in self?.handle(topic: topic, payload: payload) }
            }
            c.connect()
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) { resume(false) }
        }
        if ok {
            client = c
            driver?.close()
            driver = nil
            canSetVolume = true
            connectedIP = host
            c.subscribe("/remoteapp/mobile/#")
            status = .connected
            refresh()
        } else {
            c.close()
        }
        return ok
    }

    private func connectionLost(_ c: MQTTClient) {
        guard client === c else { return }
        client = nil
        connectedIP = nil
        if status == .connected || status == .pairing { status = .offline }
    }

    func refresh() {
        if let d = driver { d.refresh(); return }
        action("ui_service", "gettvstate")
        action("ui_service", "capability")
        action("ui_service", "sourcelist")
        action("ui_service", "applist")
        action("platform_service", "getvolume")
    }

    private func prefix() -> String? {
        if !ip.isEmpty, let dot = ip.lastIndex(of: ".") { return String(ip[...dot]) }
        guard let own = Self.wifiAddress(), let dot = own.lastIndex(of: ".") else { return nil }
        return String(own[...dot])
    }

    /// Every address on this Wi-Fi that answers on the TV remote port.
    nonisolated static func scanAll(prefix: String?) async -> [String] {
        guard let prefix else { return [] }
        return await withTaskGroup(of: String?.self) { group in
            for i in 1..<255 {
                group.addTask {
                    let host = prefix + String(i)
                    for port in TVDriver.ports where await TVDriver.tcpOpen(host, port, timeout: 1.0) { return host }
                    return nil
                }
            }
            var hits: [String] = []
            for await hit in group { if let hit { hits.append(hit) } }
            return hits
        }
    }

    /// Reads the TV's own name from its media-renderer description, when it has one.
    nonisolated static func describe(_ host: String) async -> (name: String?, model: String?) {
        guard let url = URL(string: "http://\(host):38400/MediaServer/rendererdevicedesc.xml") else { return (nil, nil) }
        var req = URLRequest(url: url)
        req.timeoutInterval = 2.5
        guard let (data, _) = try? await URLSession.shared.data(for: req) else { return (nil, nil) }
        let xml = String(decoding: data, as: UTF8.self)
        func tag(_ t: String) -> String? {
            guard let a = xml.range(of: "<\(t)>"), let b = xml.range(of: "</\(t)>", range: a.upperBound..<xml.endIndex) else { return nil }
            let v = String(xml[a.upperBound..<b.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
            return v.isEmpty ? nil : v
        }
        return (tag("friendlyName"), tag("modelName"))
    }

    /// Finds the TV by trying its remote-control port on every address of this Wi-Fi.
    nonisolated static func scan(prefix: String?) async -> String? {
        guard let prefix else { return nil }
        return await withTaskGroup(of: String?.self) { group in
            for i in 1..<255 {
                group.addTask { await probe(prefix + String(i)) ? prefix + String(i) : nil }
            }
            for await hit in group {
                if let hit { group.cancelAll(); return hit }
            }
            return nil
        }
    }

    nonisolated private static func probe(_ host: String) async -> Bool {
        await withCheckedContinuation { cont in
            let q = DispatchQueue(label: "probe")
            let conn = NWConnection(host: NWEndpoint.Host(host), port: 36669, using: .tcp)
            var done = false
            let finish: (Bool) -> Void = { ok in
                q.async {
                    if done { return }
                    done = true
                    conn.cancel()
                    cont.resume(returning: ok)
                }
            }
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(true)
                case .failed, .waiting: finish(false)
                default: break
                }
            }
            conn.start(queue: q)
            q.asyncAfter(deadline: .now() + 1.2) { finish(false) }
        }
    }

    nonisolated static func wifiAddress() -> String? {
        var result: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let iface = ptr.pointee
            guard iface.ifa_addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: iface.ifa_name)
            guard name == "en0" || name.hasPrefix("bridge") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(iface.ifa_addr, socklen_t(iface.ifa_addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            result = String(cString: host)
            if name == "en0" { break }
        }
        return result
    }

    // MARK: - Commands

    func key(_ k: String) {
        Haptics.tap()
        if let d = driver { d.key(k); return }
        guard let c = client, c.isConnected else { notConnected(); return }
        c.publish("/remoteapp/tv/remote_service/\(clientId)/actions/sendkey", k)
    }

    func text(_ code: String) {
        if let d = driver { d.text(code); return }
        guard let c = client, c.isConnected else { notConnected(); return }
        c.publish("/remoteapp/tv/remote_service/\(clientId)/actions/input", code)
    }

    func action(_ service: String, _ name: String, _ payload: String = "") {
        client?.publish("/remoteapp/tv/\(service)/\(clientId)/actions/\(name)", payload)
    }

    func setVolume(_ v: Double, force: Bool = false) {
        guard force || Date().timeIntervalSince(lastVolumeSent) > 0.12 else { return }
        lastVolumeSent = Date()
        if let d = driver { d.setVolume(Int(v)); return }
        action("platform_service", "changevolume", String(Int(v)))
    }

    func power() {
        Haptics.heavy()
        if let d = driver, d.isConnected { d.key("KEY_POWER"); return }
        if client?.isConnected == true { key("KEY_POWER"); return }
        guard !mac.isEmpty, !ip.isEmpty else {
            show("Add the TV's MAC address in settings to turn it on")
            return
        }
        wakeOnLan()
        status = .connecting
        show("Waking the TV…")
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in self?.connect() }
    }

    /// Sends the wake-up packet straight to the TV's address.
    private func wakeOnLan() {
        let hex = mac.replacingOccurrences(of: ":", with: "").replacingOccurrences(of: "-", with: "")
        guard hex.count == 12 else { return }
        var macBytes = [UInt8]()
        var idx = hex.startIndex
        for _ in 0..<6 {
            let next = hex.index(idx, offsetBy: 2)
            macBytes.append(UInt8(hex[idx..<next], radix: 16) ?? 0)
            idx = next
        }
        var packet = Data(repeating: 0xFF, count: 6)
        for _ in 0..<16 { packet.append(contentsOf: macBytes) }
        for port: UInt16 in [9, 7] {
            let conn = NWConnection(host: NWEndpoint.Host(ip), port: NWEndpoint.Port(rawValue: port)!, using: .udp)
            conn.start(queue: .global())
            for _ in 0..<3 { conn.send(content: packet, completion: .contentProcessed { _ in }) }
            DispatchQueue.global().asyncAfter(deadline: .now() + 2) { conn.cancel() }
        }
    }

    func choose(_ s: TVSource) {
        Haptics.tap()
        if let d = driver { d.source(s); show("Switching to \(s.displayName)"); return }
        let payload = json(["sourceid": s.sourceId, "sourcename": s.sourceName])
        action("ui_service", "changesource", payload)
        show("Switching to \(s.displayName)")
    }

    func launch(_ a: TVApp) {
        Haptics.tap()
        if let d = driver { d.launch(a); show("Opening \(a.displayName)"); return }
        guard client?.isConnected == true else { notConnected(); return }
        let payload = json(["appId": a.appId, "name": a.name, "url": a.url, "urlType": a.urlType, "storeType": a.storeType])
        action("ui_service", "launchapp", payload)
        show("Opening \(a.displayName)")
    }

    func pair(code: String) {
        pairingFailed = false
        action("ui_service", "authenticationcode", json(["authNum": code]))
    }

    func saveSettings(ip newIp: String, mac newMac: String) {
        ip = newIp.trimmingCharacters(in: .whitespaces)
        mac = newMac.trimmingCharacters(in: .whitespaces)
        disconnect()
        connect()
    }

    private var lastNotConnected = Date.distantPast
    private func notConnected() {
        guard Date().timeIntervalSince(lastNotConnected) > 2 else { return }
        lastNotConnected = Date()
        show("Not connected to the TV")
    }

    func show(_ message: String) {
        withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { toast = message }
        let current = message
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            if self?.toast == current { withAnimation(.easeOut(duration: 0.25)) { self?.toast = nil } }
        }
    }

    // MARK: - Incoming

    private func handle(topic: String, payload: String) {
        let obj = (try? JSONSerialization.jsonObject(with: Data(payload.utf8))) as? [String: Any]
        let arr = (try? JSONSerialization.jsonObject(with: Data(payload.utf8))) as? [[String: Any]]

        if topic.hasSuffix("/data/authentication") {
            status = .pairing
            showPairing = true
            action("ui_service", "vidaa_app_connect",
                   json(["app_version": 2, "connect_result": 0, "device_type": "Mobile App"]))
        } else if topic.hasSuffix("/data/authenticationcode") {
            if (obj?["result"] as? Int) == 1 {
                showPairing = false
                status = .connected
                show("Paired with your TV")
                refresh()
            } else {
                pairingFailed = true
                Haptics.error()
            }
        } else if topic.hasSuffix("/ui_service/state") || topic.hasSuffix("/ui_service/data/state") {
            if let obj { showState(obj) }
        } else if topic.hasSuffix("/data/capability"), let obj {
            let m = ((obj["devicemsg"] as? String) ?? "").split(separator: "_").first.map(String.init) ?? ""
            if !m.isEmpty {
                model = ((obj["vendorBrand"] as? String) == "his" ? "Hisense " : "") + m
                defaults.set(model, forKey: "model")
            }
        } else if topic.hasSuffix("/data/sourcelist"), let arr {
            sources = arr.map {
                TVSource(sourceId: "\($0["sourceid"] ?? "")",
                         sourceName: "\($0["sourcename"] ?? "")",
                         displayName: "\($0["displayname"] ?? $0["sourcename"] ?? "")",
                         hasSignal: "\($0["is_signal"] ?? "0")" == "1")
            }
        } else if topic.hasSuffix("/data/applist"), let arr {
            let list = arr.compactMap { a -> TVApp? in
                guard let name = a["name"] as? String, !name.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
                return TVApp(name: name, url: (a["url"] as? String) ?? "", appId: "\(a["appId"] ?? "")",
                             urlType: (a["urlType"] as? Int) ?? 37, storeType: (a["storeType"] as? Int) ?? 0)
            }
            if !list.isEmpty { apps = Array(list.prefix(16)) }
        } else if topic.contains("volumechange") || topic.hasSuffix("/data/volume") || topic.hasSuffix("/ui_service/volume") {
            if let v = obj?["volume_value"] as? Int {
                withAnimation(.easeOut(duration: 0.25)) { volume = Double(v) }
            }
        } else if topic.hasSuffix("/actions/tvsleep") {
            nowTitle = "Standby"
            nowSubtitle = "The TV is going to sleep"
        }
    }

    private func showState(_ s: [String: Any]) {
        let type = (s["statetype"] as? String) ?? ""
        let str: (String) -> String = { "\(s[$0] ?? "")" }
        switch type {
        case "livetv":
            nowTitle = str("channel_name").isEmpty ? "Live TV" : str("channel_name")
            let prog = str("progname")
            nowSubtitle = (prog.isEmpty ? "Live TV" : prog) + " · Channel " + str("channel_num")
        case "sourceswitch":
            nowTitle = str("displayname").isEmpty ? str("sourcename") : str("displayname")
            nowSubtitle = str("sourcename") + (str("is_signal") == "1" ? " · signal" : " · no signal")
        case "remote_launcher":
            nowTitle = "Home screen"; nowSubtitle = "Pick something to watch"
        case "remote_setting":
            nowTitle = "Settings"; nowSubtitle = "A settings menu is open"
        default:
            if type.hasPrefix("fake_sleep") {
                nowTitle = "Standby"
                nowSubtitle = type.hasSuffix("1") ? "Waking up…" : "Tap power to turn it on"
            } else {
                let name = str("name")
                nowTitle = name.isEmpty ? (type.isEmpty ? "Watching" : type.replacingOccurrences(of: "_", with: " ").capitalized) : name
                nowSubtitle = "On the TV now"
            }
        }
    }

    private func json(_ dict: [String: Any]) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: dict) else { return "{}" }
        return String(decoding: d, as: UTF8.self)
    }
}

enum Haptics {
    static var enabled: Bool { UserDefaults.standard.object(forKey: "haptics") as? Bool ?? true }
    static func tap() { if enabled { UIImpactFeedbackGenerator(style: .light).impactOccurred() } }
    static func heavy() { if enabled { UIImpactFeedbackGenerator(style: .medium).impactOccurred() } }
    static func tick() { if enabled { UISelectionFeedbackGenerator().selectionChanged() } }
    static func error() { if enabled { UINotificationFeedbackGenerator().notificationOccurred(.error) } }
}
