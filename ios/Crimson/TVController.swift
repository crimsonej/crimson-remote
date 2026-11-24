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

    var displayName: String {
        name.trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: #"\s+\d+(\.\d+)*$"#, with: "", options: .regularExpression)
    }

    /// Each service's own site icon stands in for its logo.
    var logoURL: URL? {
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

    private var client: MQTTClient?
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

    func connect() {
        connectTask?.cancel()
        connectTask = Task {
            if client?.isConnected == true { return }
            status = .connecting
            if !ip.isEmpty, await attempt(ip) { return }
            status = .searching
            if let found = await Self.scan(prefix: prefix()), await attempt(found) {
                ip = found
                return
            }
            status = .offline
            nowTitle = "TV is off"
            nowSubtitle = mac.isEmpty ? "Turn it on, and keep the iPhone on the same Wi-Fi" : "Tap power to wake it"
        }
    }

    func disconnect() {
        client?.close()
        client = nil
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
        if status == .connected || status == .pairing { status = .offline }
    }

    func refresh() {
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
        guard let c = client, c.isConnected else { notConnected(); return }
        c.publish("/remoteapp/tv/remote_service/\(clientId)/actions/sendkey", k)
    }

    func text(_ code: String) {
        guard let c = client, c.isConnected else { notConnected(); return }
        c.publish("/remoteapp/tv/remote_service/\(clientId)/actions/input", code)
    }

    func action(_ service: String, _ name: String, _ payload: String = "") {
        client?.publish("/remoteapp/tv/\(service)/\(clientId)/actions/\(name)", payload)
    }

    func setVolume(_ v: Double, force: Bool = false) {
        guard force || Date().timeIntervalSince(lastVolumeSent) > 0.12 else { return }
        lastVolumeSent = Date()
        action("platform_service", "changevolume", String(Int(v)))
    }

    func power() {
        Haptics.heavy()
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
        let payload = json(["sourceid": s.sourceId, "sourcename": s.sourceName])
        action("ui_service", "changesource", payload)
        show("Switching to \(s.displayName)")
    }

    func launch(_ a: TVApp) {
        Haptics.tap()
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
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func heavy() { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
    static func tick() { UISelectionFeedbackGenerator().selectionChanged() }
    static func error() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
}
