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

    /// The TV's own icon when it gives one, otherwise each service's site icon.
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

/// A TV found on this Wi-Fi right now.
struct TVDevice: Identifiable, Hashable {
    var id: String { ip }
    let ip: String
    var name: String
    var model: String?
    var brand: TVBrand = .hisense
    var mac: String = ""
}

/// A TV this iPhone has connected to. Saved when the connection is made and
/// recognised by its MAC, so it is found again when the router changes its address.
struct SavedTV: Codable, Identifiable, Hashable {
    var id: String
    var ip: String
    var name: String
    var brand: TVBrand
    var model: String
    var mac: String
    var lastSeen: Date

    var canWake: Bool { mac.count == 17 }
    var title: String { name.isEmpty ? "\(brand.label) TV" : name }

    static func normalMac(_ raw: String?) -> String {
        let hex = (raw ?? "").uppercased().filter { "0123456789ABCDEF".contains($0) }
        guard hex.count == 12, hex != "000000000000" else { return "" }
        var parts: [String] = []
        var i = hex.startIndex
        while i < hex.endIndex { let j = hex.index(i, offsetBy: 2); parts.append(String(hex[i..<j])); i = j }
        return parts.joined(separator: ":")
    }

    static func idFor(mac: String, ip: String) -> String {
        let m = normalMac(mac)
        return m.isEmpty ? "ip:\(ip)" : m.replacingOccurrences(of: ":", with: "").lowercased()
    }
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
    enum Status: Equatable {
        case idle, searching, connecting, connected, offline, pairing, disconnected, none, choose, waking
    }

    @Published var status: Status = .idle
    @Published var statusNote: String?
    @Published var nowTitle = "Not connected"
    @Published var nowSubtitle = " "
    @Published var volume: Double = 0
    @Published var apps: [TVApp] = []
    @Published var sources: [TVSource] = []
    @Published var showPairing = false
    @Published var pairingFailed = false
    @Published var toast: String?
    @Published var devices: [TVDevice] = []
    @Published var saved: [SavedTV] = []
    @Published var currentID: String?
    @Published var scanning = false
    @Published var connectedIP: String?
    @Published var canSetVolume = true
    @Published var chooseTV = false

    private var client: MQTTClient?
    /// The non-Hisense TV being controlled (Roku, Samsung, LG), if any.
    private var driver: TVDriver?
    private var userDisconnected = false
    private let defaults = UserDefaults.standard
    private var lastVolumeSent = Date.distantPast
    private var connectTask: Task<Void, Never>?

    var isLive: Bool { client?.isConnected == true || driver?.isConnected == true }
    var current: SavedTV? { saved.first { $0.id == currentID } }
    var displayName: String { current?.title ?? "No TV yet" }

    private var clientId: String {
        if let id = defaults.string(forKey: "cid") { return id }
        let id = "CrimsonIOS-" + UUID().uuidString.prefix(8).lowercased()
        defaults.set(id, forKey: "cid")
        return id
    }

    init() {
        if let d = defaults.data(forKey: "tvs"), let list = try? JSONDecoder().decode([SavedTV].self, from: d) { saved = list }
        currentID = defaults.string(forKey: "current")
    }

    // MARK: - Saved TVs

    private func persist() {
        if let d = try? JSONEncoder().encode(saved) { defaults.set(d, forKey: "tvs") }
        defaults.set(currentID, forKey: "current")
    }

    /// Saves or updates a TV; an entry saved by address is merged once its MAC is known.
    @discardableResult
    func save(ip: String, name: String?, brand: TVBrand, model: String?, mac: String?, makeCurrent: Bool) -> SavedTV {
        let m = SavedTV.normalMac(mac)
        let newID = SavedTV.idFor(mac: m, ip: ip)
        var tv = SavedTV(id: newID, ip: ip, name: name ?? "", brand: brand, model: model ?? "", mac: m, lastSeen: Date())
        var oldIDs: [String] = []
        saved.removeAll { old in
            let same = old.id == newID || (!m.isEmpty && old.mac == m) || (old.id.hasPrefix("ip:") && old.ip == ip && old.brand == brand)
            if same {
                oldIDs.append(old.id)
                if tv.mac.isEmpty { tv.mac = old.mac }
                if tv.name.isEmpty { tv.name = old.name }
                if tv.model.isEmpty { tv.model = old.model }
            }
            return same
        }
        tv.id = SavedTV.idFor(mac: tv.mac, ip: ip)
        // Keep pairing tokens when an entry is re-keyed by its MAC.
        for old in oldIDs where old != tv.id {
            for k in ["samsung_token_", "lg_key_"] {
                if let v = defaults.string(forKey: k + old), defaults.string(forKey: k + tv.id) == nil { defaults.set(v, forKey: k + tv.id) }
            }
        }
        saved.append(tv)
        if makeCurrent || currentID == nil || oldIDs.contains(currentID ?? "") { currentID = tv.id }
        persist()
        return tv
    }

    func forget(_ tv: SavedTV) {
        if tv.id == currentID { disconnectNow() ; currentID = nil }
        saved.removeAll { $0.id == tv.id }
        defaults.removeObject(forKey: "samsung_token_\(tv.id)")
        defaults.removeObject(forKey: "lg_key_\(tv.id)")
        persist()
        if current == nil { status = .idle; apps = []; sources = []; nowTitle = "Not connected"; nowSubtitle = " " }
    }

    // MARK: - Connection

    /// Automatic connect (app opened). Does nothing after you pressed Disconnect.
    func connect() {
        if userDisconnected { return }
        if isLive { status = .connected; return }
        connectTask?.cancel()
        connectTask = Task {
            let cur = current
            status = cur == nil ? .searching : .connecting
            if let cur, await open(cur) { Task { await discover() }; return }
            status = .searching
            await discover()
            if let cur {
                if let d = findSaved(cur), await openFound(d) { return }
                status = .offline
                return
            }
            switch devices.count {
            case 0: status = .none
            case 1: if !(await openFound(devices[0])) { status = .offline }
            default: status = .choose; chooseTV = true
            }
        }
    }

    /// Connect / Reconnect button: start again from scratch.
    func reconnect() {
        userDisconnected = false
        statusNote = nil
        closeConnection()
        connect()
    }

    /// Disconnect button: stays disconnected until you connect again.
    func disconnectNow() {
        userDisconnected = true
        connectTask?.cancel()
        closeConnection()
        status = .disconnected
        statusNote = nil
    }

    /// Closes the connection quietly (app sent to the background).
    func disconnect() { closeConnection() }

    private func closeConnection() {
        client?.close()
        client = nil
        driver?.close()
        driver = nil
        connectedIP = nil
    }

    private func findSaved(_ tv: SavedTV) -> TVDevice? {
        if !tv.mac.isEmpty, let d = devices.first(where: { $0.mac == tv.mac }) { return d }
        return devices.first { $0.ip == tv.ip && $0.brand == tv.brand }
    }

    /// Saves a found TV and connects to it.
    func openFound(_ d: TVDevice) async -> Bool {
        let tv = save(ip: d.ip, name: d.name, brand: d.brand, model: d.model, mac: d.mac, makeCurrent: true)
        return await open(tv)
    }

    private func open(_ tv: SavedTV) async -> Bool {
        status = .connecting
        return tv.brand == .hisense ? await attempt(tv.ip) : await attemptDriver(tv)
    }

    /// Switch to a TV that was just found.
    func select(_ d: TVDevice) {
        Haptics.tap()
        userDisconnected = false
        closeConnection()
        nowTitle = "Connecting…"
        nowSubtitle = d.name
        connectTask?.cancel()
        connectTask = Task { if !(await openFound(d)) { status = .offline } }
    }

    /// Switch to a saved TV, even if it wasn't found just now.
    func select(saved tv: SavedTV) {
        Haptics.tap()
        currentID = tv.id
        persist()
        reconnect()
    }

    /// Adds a TV typed in by address.
    func add(address: String) {
        let host = address.trimmingCharacters(in: .whitespaces)
        connectTask?.cancel()
        connectTask = Task {
            status = .connecting
            guard let info = await TVDriver.identify(host) else {
                status = .offline
                show("No supported TV answered at \(host)")
                return
            }
            userDisconnected = false
            closeConnection()
            let d = TVDevice(ip: host, name: info.1 ?? "\(info.0.label) TV", model: info.2, brand: info.0, mac: SavedTV.normalMac(info.3))
            if !(await openFound(d)) { status = .offline }
        }
    }

    /// Learns the TV's name, model and MAC after connecting and saves them.
    private func learn(_ host: String) {
        Task {
            guard let info = await TVDriver.identify(host) else { return }
            save(ip: host, name: info.1, brand: info.0, model: info.2, mac: info.3, makeCurrent: true)
        }
    }

    /// Scans the Wi-Fi for every supported TV and reads its brand, name and MAC.
    func discover() async {
        scanning = true
        defer { scanning = false }
        let hosts = await Self.scanAll(prefix: prefix())
        var found: [TVDevice] = []
        for host in hosts {
            guard let info = await TVDriver.identify(host) else { continue }
            let mac = SavedTV.normalMac(info.3)
            found.append(TVDevice(ip: host, name: info.1 ?? "\(info.0.label) TV", model: info.2, brand: info.0, mac: mac))
            // A saved TV that moved to a new address gets its address updated.
            if !mac.isEmpty, let i = saved.firstIndex(where: { $0.mac == mac }), saved[i].ip != host {
                saved[i].ip = host
                persist()
            }
        }
        withAnimation(.easeOut(duration: 0.25)) {
            devices = found.sorted { $0.ip.compare($1.ip, options: .numeric) == .orderedAscending }
        }
    }

    private func attemptDriver(_ tv: SavedTV) async -> Bool {
        let host = tv.ip
        var ev = DriverEvents()
        ev.connected = { [weak self] in self?.status = .connected; self?.connectedIP = host }
        ev.disconnected = { [weak self] in
            guard let self, self.driver?.ip == host else { return }
            self.driver = nil
            self.connectedIP = nil
            if !self.userDisconnected { self.status = .offline }
        }
        ev.prompt = { [weak self] m in
            self?.status = .pairing
            self?.statusNote = m
            self?.nowTitle = "Check your TV"
            self?.nowSubtitle = m
        }
        ev.state = { [weak self] t, s in self?.nowTitle = t; self?.nowSubtitle = s }
        ev.volume = { [weak self] v in withAnimation(.easeOut(duration: 0.25)) { self?.volume = Double(v) } }
        ev.apps = { [weak self] a in self?.apps = Array(a.prefix(16)) }
        ev.sources = { [weak self] s in self?.sources = s }
        ev.model = { [weak self] m in
            guard let self, let cur = self.current else { return }
            self.save(ip: cur.ip, name: cur.name, brand: cur.brand, model: m.trimmingCharacters(in: .whitespaces), mac: cur.mac, makeCurrent: true)
        }
        ev.mac = { [weak self] m in
            guard let self, let cur = self.current else { return }
            self.save(ip: cur.ip, name: cur.name, brand: cur.brand, model: cur.model, mac: m, makeCurrent: true)
        }
        guard let d = TVDriver.make(tv.brand, ip: host, id: tv.id, events: ev) else { return false }
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
            learn(host)
        } else {
            c.close()
        }
        return ok
    }

    private func connectionLost(_ c: MQTTClient) {
        guard client === c else { return }
        client = nil
        connectedIP = nil
        if !userDisconnected && (status == .connected || status == .pairing) { status = .offline }
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
        guard let own = Self.wifiAddress(), let dot = own.lastIndex(of: ".") else { return nil }
        return String(own[...dot])
    }

    /// Every address on this Wi-Fi that answers on a TV remote port.
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
        wake()
    }

    /// Sends the wake-up signal to the saved TV, then keeps trying to connect.
    func wake() {
        guard let tv = current else { show("Choose a TV first"); return }
        guard tv.canWake else {
            show("Connect to this TV once while it's on, then Crimson can turn it on")
            return
        }
        Haptics.heavy()
        userDisconnected = false
        status = .waking
        statusNote = nil
        connectTask?.cancel()
        connectTask = Task {
            await sendWake(tv)
            for _ in 0..<10 {
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                if Task.isCancelled { return }
                if await open(tv) { return }
                // The TV may come back on a new address.
                if let d = devices.first(where: { $0.mac == tv.mac }), d.ip != tv.ip, await openFound(d) { return }
            }
            if !isLive {
                status = .offline
                statusNote = "It didn't wake. Turn on \u{201C}Wake on LAN\u{201D} or \u{201C}Wake on Wi-Fi\u{201D} in the TV's network settings, or use its remote."
            }
        }
    }

    /// Wake-on-LAN, sent from this iPhone only. Apps installed outside the App Store may not
    /// broadcast, so the packet goes straight to the TV's last address (and the subnet broadcast,
    /// which iOS may block). That wakes TVs that keep their network on in standby.
    private func sendWake(_ tv: SavedTV) async {
        let hex = tv.mac.replacingOccurrences(of: ":", with: "")
        var bytes = [UInt8]()
        var i = hex.startIndex
        while i < hex.endIndex { let j = hex.index(i, offsetBy: 2); bytes.append(UInt8(hex[i..<j], radix: 16) ?? 0); i = j }
        var packet = Data(repeating: 0xFF, count: 6)
        for _ in 0..<16 { packet.append(contentsOf: bytes) }
        var targets = [tv.ip]
        if let p = prefix() { targets.append(p + "255") }
        for host in targets {
            for port: UInt16 in [9, 7] {
                let conn = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: .udp)
                conn.start(queue: .global())
                for _ in 0..<3 { conn.send(content: packet, completion: .contentProcessed { _ in }) }
                DispatchQueue.global().asyncAfter(deadline: .now() + 2) { conn.cancel() }
            }
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

    private var lastNotConnected = Date.distantPast
    private func notConnected() {
        guard Date().timeIntervalSince(lastNotConnected) > 2 else { return }
        lastNotConnected = Date()
        show(status == .connecting || status == .searching ? "Still connecting…" : "Not connected. Use the buttons at the top to connect.")
    }

    func show(_ message: String) {
        withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { toast = message }
        let current = message
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.2) { [weak self] in
            if self?.toast == current { withAnimation(.easeOut(duration: 0.25)) { self?.toast = nil } }
        }
    }

    // MARK: - Incoming (Hisense)

    private func handle(topic: String, payload: String) {
        let obj = (try? JSONSerialization.jsonObject(with: Data(payload.utf8))) as? [String: Any]
        let arr = (try? JSONSerialization.jsonObject(with: Data(payload.utf8))) as? [[String: Any]]

        if topic.hasSuffix("/data/authentication") {
            status = .pairing
            statusNote = "Type the 4-digit code shown on the TV"
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
            if !m.isEmpty, let cur = current {
                save(ip: cur.ip, name: cur.name, brand: cur.brand, model: m, mac: cur.mac, makeCurrent: true)
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
            apps = Array(list.prefix(16))
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
