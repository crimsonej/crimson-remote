import Foundation
import Network
import SwiftUI
import PhotosUI
import AVKit
import AVFoundation
import UniformTypeIdentifiers

/// Casts photos and videos to the TV with DLNA: the iPhone serves the file on a
/// small web server and tells the TV's media renderer to play it. One shared
/// instance, so casting keeps going when you switch screens.
@MainActor
final class Caster: ObservableObject {
    static let shared = Caster()

    enum Phase: Equatable { case idle, preparing, sending, playing, paused, stopped, failed }

    @Published var phase: Phase = .idle
    @Published var message = ""
    @Published var title = ""
    @Published var isPhoto = false
    @Published var position: Double = 0
    @Published var duration: Double = 0
    @Published var thumbnail: UIImage?

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "crimson.cast")
    private var fileURL: URL?
    private var mime = "application/octet-stream"
    private var controlURL: URL?
    private var tvIP = ""
    private var poller: Task<Void, Never>?
    private let port: UInt16 = 8790

    var active: Bool { [.sending, .playing, .paused].contains(phase) }

    /// Picks up a Photos item, converts it if needed, and casts it.
    func cast(_ item: PhotosPickerItem, tvIP: String) async {
        phase = .preparing
        message = "Getting it ready…"
        let isVideo = item.supportedContentTypes.contains { $0.conforms(to: .movie) }
        if isVideo, let movie = try? await item.loadTransferable(type: PickedMovie.self) {
            thumbnail = await Self.thumbnail(of: movie.url)
            message = "Converting the video for the TV…"
            guard let mp4 = await Self.toMP4(movie.url) else { fail("Couldn't convert this video"); return }
            await cast(mp4, mime: "video/mp4", title: "Video", photo: false, tvIP: tvIP)
        } else if let data = try? await item.loadTransferable(type: Data.self),
                  let image = UIImage(data: data), let jpeg = Self.fit(image).jpegData(compressionQuality: 0.88) {
            thumbnail = image
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("crimson-photo-\(Int(Date().timeIntervalSince1970)).jpg")
            try? jpeg.write(to: url)
            await cast(url, mime: "image/jpeg", title: "Photo", photo: true, tvIP: tvIP)
        } else {
            fail("Couldn't read that item")
        }
    }

    private func cast(_ file: URL, mime: String, title: String, photo: Bool, tvIP: String) async {
        phase = .sending
        message = "Sending to the TV…"
        self.title = title
        isPhoto = photo
        position = 0
        duration = 0
        do {
            guard let phone = TVController.wifiAddress() else { throw CastError("Connect the iPhone to Wi-Fi first") }
            try await prepare(tvIP)
            try startServer()
            fileURL = file
            self.mime = mime
            let url = "http://\(phone):\(port)/media/\(Int(Date().timeIntervalSince1970)).\(file.pathExtension)"
            _ = try? await soap("Stop", "<InstanceID>0</InstanceID>")
            _ = try await soap("SetAVTransportURI",
                               "<InstanceID>0</InstanceID><CurrentURI>\(esc(url))</CurrentURI><CurrentURIMetaData>\(esc(didl(url, title)))</CurrentURIMetaData>")
            _ = try await soap("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            phase = .playing
            message = photo ? "Showing on the TV" : "Playing on the TV"
            startPolling()
        } catch {
            fail((error as? CastError)?.message ?? "Couldn't cast: \(error.localizedDescription)")
        }
    }

    func playPause() {
        Task {
            do {
                if phase == .playing { _ = try await soap("Pause", "<InstanceID>0</InstanceID>"); phase = .paused }
                else { _ = try await soap("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>"); phase = .playing }
            } catch { fail("The TV stopped responding") }
        }
    }

    func stop() {
        Task {
            _ = try? await soap("Stop", "<InstanceID>0</InstanceID>")
            poller?.cancel()
            phase = .stopped
            message = "Stopped"
        }
    }

    func seek(to seconds: Double) {
        let t = Int(seconds)
        let target = String(format: "%02d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
        Task { _ = try? await soap("Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>\(target)</Target>") }
    }

    private func fail(_ m: String) {
        poller?.cancel()
        phase = .failed
        message = m
    }

    /// Follows what the TV is doing: playing, paused, stopped, and how far along.
    private func startPolling() {
        poller?.cancel()
        poller = Task { [weak self] in
            var misses = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self else { return }
                guard let info = try? await self.soap("GetTransportInfo", "<InstanceID>0</InstanceID>") else {
                    misses += 1
                    if misses > 5 { self.fail("Lost contact with the TV"); return }
                    continue
                }
                misses = 0
                let state = Self.tag(info, "CurrentTransportState") ?? ""
                switch state {
                case "PLAYING": self.phase = .playing
                case "PAUSED_PLAYBACK": self.phase = .paused
                case "STOPPED", "NO_MEDIA_PRESENT":
                    if self.phase == .playing && !self.isPhoto && self.duration > 0 && self.position >= self.duration - 2 {
                        self.phase = .stopped; self.message = "Finished"; return
                    }
                default: break
                }
                if !self.isPhoto, let pos = try? await self.soap("GetPositionInfo", "<InstanceID>0</InstanceID>") {
                    self.position = Self.seconds(Self.tag(pos, "RelTime"))
                    let d = Self.seconds(Self.tag(pos, "TrackDuration"))
                    if d > 0 { self.duration = d }
                }
            }
        }
    }

    // MARK: - Media preparation

    /// Converts any video to H.264 MP4 at up to 1080p, which nearly every TV can play.
    nonisolated static func toMP4(_ url: URL) async -> URL? {
        let asset = AVURLAsset(url: url)
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPreset1920x1080) else { return nil }
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("crimson-video-\(Int(Date().timeIntervalSince1970)).mp4")
        try? FileManager.default.removeItem(at: out)
        export.outputURL = out
        export.outputFileType = .mp4
        export.shouldOptimizeForNetworkUse = true
        await export.export()
        return export.status == .completed ? out : nil
    }

    nonisolated static func thumbnail(of url: URL) async -> UIImage? {
        let gen = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: 400, height: 400)
        guard let cg = try? gen.copyCGImage(at: CMTime(seconds: 1, preferredTimescale: 600), actualTime: nil) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// Scales very large photos down to 4K so the TV opens them quickly.
    static func fit(_ image: UIImage) -> UIImage {
        let maxSide: CGFloat = 3840
        let s = max(image.size.width, image.size.height)
        guard s > maxSide else { return image }
        let k = maxSide / s
        let size = CGSize(width: image.size.width * k, height: image.size.height * k)
        return UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }

    // MARK: - UPnP

    private func prepare(_ ip: String) async throws {
        if let c = controlURL, c.host == ip { return }
        tvIP = ip
        // Hisense, then Samsung's description; other TVs are found through the TV's own list.
        for desc in ["http://\(ip):38400/MediaServer/rendererdevicedesc.xml", "http://\(ip):9197/dmr"] {
            if let control = await Self.controlURL(from: desc) { controlURL = control; return }
        }
        throw CastError("This TV doesn't accept casting right now")
    }

    nonisolated private static func controlURL(from desc: String) async -> URL? {
        guard let u = URL(string: desc) else { return nil }
        var req = URLRequest(url: u)
        req.timeoutInterval = 4
        guard let (data, _) = try? await URLSession.shared.data(for: req) else { return nil }
        let xml = String(decoding: data, as: UTF8.self)
        guard let svc = xml.range(of: ":service:AVTransport:"),
              let a = xml.range(of: "<controlURL>", range: svc.upperBound..<xml.endIndex),
              let b = xml.range(of: "</controlURL>", range: a.upperBound..<xml.endIndex) else { return nil }
        let path = xml[a.upperBound..<b.lowerBound].trimmingCharacters(in: .whitespaces)
        if path.hasPrefix("http") { return URL(string: path) }
        let base = "\(u.scheme ?? "http")://\(u.host ?? ""):\(u.port ?? 80)"
        return URL(string: base + (path.hasPrefix("/") ? "" : "/") + path)
    }

    private func soap(_ action: String, _ args: String) async throws -> String {
        guard let url = controlURL else { throw CastError("Choose something to cast first") }
        let body = """
        <?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:\(action) xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">\(args)</u:\(action)></s:Body></s:Envelope>
        """
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 8
        req.setValue("text/xml; charset=\"utf-8\"", forHTTPHeaderField: "Content-Type")
        req.setValue("\"urn:schemas-upnp-org:service:AVTransport:1#\(action)\"", forHTTPHeaderField: "SOAPACTION")
        req.httpBody = Data(body.utf8)
        let (data, resp) = try await URLSession.shared.data(for: req)
        if let http = resp as? HTTPURLResponse, http.statusCode >= 400 {
            throw CastError("The TV refused \(action) (\(http.statusCode))")
        }
        return String(decoding: data, as: UTF8.self)
    }

    private func didl(_ url: String, _ title: String) -> String {
        let cls = mime.hasPrefix("image") ? "object.item.imageItem.photo" : mime.hasPrefix("audio") ? "object.item.audioItem.musicTrack" : "object.item.videoItem"
        let flags = mime.hasPrefix("image") ? "DLNA.ORG_OP=00;DLNA.ORG_FLAGS=00900000000000000000000000000000"
            : "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"1\" parentID=\"0\" restricted=\"1\"><dc:title>\(esc(title))</dc:title><upnp:class>\(cls)</upnp:class><res protocolInfo=\"http-get:*:\(mime):\(flags)\">\(esc(url))</res></item></DIDL-Lite>"
    }

    private func esc(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
    }

    nonisolated static func tag(_ xml: String, _ t: String) -> String? {
        guard let a = xml.range(of: "<\(t)>"), let b = xml.range(of: "</\(t)>", range: a.upperBound..<xml.endIndex) else { return nil }
        return String(xml[a.upperBound..<b.lowerBound])
    }

    nonisolated static func seconds(_ hms: String?) -> Double {
        guard let hms else { return 0 }
        let p = hms.split(separator: ":").compactMap { Double($0) }
        guard p.count == 3 else { return 0 }
        return p[0] * 3600 + p[1] * 60 + p[2]
    }

    // MARK: - File server

    private func startServer() throws {
        if listener != nil { return }
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        let l = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
        l.newConnectionHandler = { [weak self] conn in
            Task { @MainActor in self?.serve(conn) }
        }
        l.stateUpdateHandler = { [weak self] state in
            if case .failed = state { Task { @MainActor in self?.listener = nil } }
        }
        l.start(queue: queue)
        listener = l
    }

    private func serve(_ conn: NWConnection) {
        let file = fileURL
        let type = mime
        conn.start(queue: queue)
        conn.receive(minimumIncompleteLength: 1, maximumLength: 16384) { data, _, _, _ in
            let request = String(decoding: data ?? Data(), as: UTF8.self)
            guard let file, let handle = try? FileHandle(forReadingFrom: file),
                  let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.int64Value else {
                conn.send(content: Data("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".utf8), completion: .contentProcessed { _ in conn.cancel() })
                return
            }
            var from: Int64 = 0, to: Int64 = size - 1
            var partial = false
            for line in request.components(separatedBy: "\r\n") where line.lowercased().hasPrefix("range:") {
                let r = line.dropFirst(6).trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "bytes=", with: "")
                let parts = r.split(separator: "-", omittingEmptySubsequences: false)
                if let f = Int64(parts.first ?? "") { from = f; partial = true }
                if parts.count > 1, let t = Int64(parts[1]) { to = min(t, size - 1); partial = true }
            }
            let length = max(0, to - from + 1)
            var head = partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n"
            head += "Content-Type: \(type)\r\nContent-Length: \(length)\r\nAccept-Ranges: bytes\r\n"
            if partial { head += "Content-Range: bytes \(from)-\(to)/\(size)\r\n" }
            head += "transferMode.dlna.org: \(type.hasPrefix("image") ? "Interactive" : "Streaming")\r\n"
            head += "contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\nConnection: close\r\n\r\n"
            let isHead = request.hasPrefix("HEAD")
            conn.send(content: Data(head.utf8), completion: .contentProcessed { _ in
                if isHead { conn.cancel(); return }
                try? handle.seek(toOffset: UInt64(from))
                Caster.pump(conn, handle, remaining: length)
            })
        }
    }

    nonisolated private static func pump(_ conn: NWConnection, _ handle: FileHandle, remaining: Int64) {
        guard remaining > 0 else { try? handle.close(); conn.cancel(); return }
        let chunk = handle.readData(ofLength: Int(min(remaining, 256 * 1024)))
        guard !chunk.isEmpty else { try? handle.close(); conn.cancel(); return }
        conn.send(content: chunk, completion: .contentProcessed { error in
            if error != nil { try? handle.close(); conn.cancel(); return }
            pump(conn, handle, remaining: remaining - Int64(chunk.count))
        })
    }
}

struct CastError: Error {
    let message: String
    init(_ m: String) { message = m }
}

/// A picked video copied to a temporary file.
struct PickedMovie: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let dest = FileManager.default.temporaryDirectory.appendingPathComponent("cast-" + received.file.lastPathComponent)
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.copyItem(at: received.file, to: dest)
            return PickedMovie(url: dest)
        }
    }
}

/// AirPlay picker; it only lists receivers that support AirPlay.
struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.activeTintColor = UIColor(Theme.crimson)
        v.tintColor = UIColor(Theme.text)
        v.prioritizesVideoDevices = true
        return v
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

// MARK: - Cast screen

struct CastScreen: View {
    @EnvironmentObject var tv: TVController
    @ObservedObject private var caster = Caster.shared
    @ObservedObject private var desktop = DesktopModel.shared
    @State private var item: PhotosPickerItem?
    @State private var showDesktop = false
    @State private var scrubbing = false
    @State private var scrub: Double = 0

    var body: some View {
        ZStack {
            AppBackground()
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 22) {
                    ScreenHeader(title: "Cast")

                    if caster.phase != .idle { nowCasting }

                    if !tv.features.isEmpty && !tv.features.contains(.cast) {
                        Text("This TV doesn't accept photos and videos from phones. Use AirPlay or Crimson Desktop below if it supports them.")
                            .font(.system(size: 13)).foregroundStyle(Theme.sub)
                            .padding(16).frame(maxWidth: .infinity, alignment: .leading).card(radius: 20)
                    } else {
                    PhotosPicker(selection: $item, matching: .any(of: [.images, .videos])) {
                        HStack(spacing: 16) {
                            Image(systemName: "photo.on.rectangle.angled")
                                .font(.system(size: 24, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(width: 58, height: 58)
                                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.crimsonGradient))
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Photos & videos").font(.system(size: 17, weight: .bold)).foregroundStyle(Theme.text)
                                Text("Pick something to show on the TV").font(.system(size: 13)).foregroundStyle(Theme.sub)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").font(.system(size: 14, weight: .bold)).foregroundStyle(Theme.faint)
                        }
                        .padding(16)
                        .card(radius: 24)
                    }
                    .buttonStyle(PressStyle(scale: 0.98))
                    .disabled(caster.phase == .preparing || caster.phase == .sending || tv.status != .connected)
                    .opacity(tv.status == .connected ? 1 : 0.5)
                    }

                    Text("MORE WAYS").sectionLabel().padding(.top, 4)
                    VStack(spacing: 0) {
                        HStack(spacing: 14) {
                            rowIcon("airplayvideo")
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Mirror with AirPlay").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                                Text("Only for TVs that support AirPlay").font(.system(size: 12)).foregroundStyle(Theme.sub)
                            }
                            Spacer()
                            AirPlayButton().frame(width: 40, height: 40)
                        }
                        .padding(14)
                        Divider().padding(.leading, 66)
                        Button { Haptics.tap(); showDesktop = true } label: {
                            HStack(spacing: 14) {
                                rowIcon("desktopcomputer")
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Crimson Desktop").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                                    Text(desktop.connected ? "On the TV now \u{00B7} open the controls" : "Over AirPlay or an HDMI adapter")
                                        .font(.system(size: 12)).foregroundStyle(Theme.sub)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").font(.system(size: 13, weight: .bold)).foregroundStyle(Theme.faint)
                            }
                            .padding(14)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(PressStyle(scale: 0.98))
                    }
                    .card(radius: 22)
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 30)
            }
        }
        .onChange(of: item) { newItem in
            guard let newItem else { return }
            guard let ip = tv.connectedIP ?? tv.current?.ip else { tv.show("Connect to a TV first"); return }
            Task { await caster.cast(newItem, tvIP: ip); item = nil }
        }
        .fullScreenCover(isPresented: $showDesktop) {
            DesktopControls()
                .environmentObject(DesktopModel.shared)
                .background(Theme.bg.ignoresSafeArea())
        }
    }

    private var nowCasting: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.surface2)
                    if let t = caster.thumbnail {
                        Image(uiImage: t).resizable().scaledToFill()
                    } else {
                        Image(systemName: caster.isPhoto ? "photo" : "film").foregroundStyle(Theme.faint)
                    }
                }
                .frame(width: 64, height: 64)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text("NOW CASTING").sectionLabel()
                    Text(caster.message).font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(caster.phase == .failed ? Theme.red : Theme.text)
                        .lineLimit(2)
                }
                Spacer()
                if caster.phase == .preparing || caster.phase == .sending { ProgressView().tint(Theme.crimson) }
            }
            if !caster.isPhoto && caster.duration > 0 {
                VStack(spacing: 4) {
                    Slider(value: Binding(get: { scrubbing ? scrub : caster.position },
                                          set: { scrub = $0 }),
                           in: 0...max(caster.duration, 1)) { editing in
                        scrubbing = editing
                        if !editing { caster.seek(to: scrub) }
                    }
                    .tint(Theme.crimson)
                    HStack {
                        Text(time(caster.position)); Spacer(); Text(time(caster.duration))
                    }
                    .font(.system(size: 11).monospacedDigit()).foregroundStyle(Theme.sub)
                }
            }
            if caster.active {
                HStack(spacing: 10) {
                    if !caster.isPhoto {
                        castButton(caster.phase == .playing ? "pause.fill" : "play.fill",
                                   caster.phase == .playing ? "Pause" : "Play", primary: true) { caster.playPause() }
                    }
                    castButton("stop.fill", "Stop casting", primary: caster.isPhoto) { caster.stop() }
                }
            }
        }
        .padding(16)
        .card(radius: 24)
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    private func castButton(_ icon: String, _ label: String, primary: Bool, _ action: @escaping () -> Void) -> some View {
        Button { Haptics.tap(); action() } label: {
            Label(label, systemImage: icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(primary ? Color.white : Theme.text)
                .frame(maxWidth: .infinity).frame(height: 46)
                .background(Capsule().fill(primary ? AnyShapeStyle(Theme.crimsonGradient) : AnyShapeStyle(Theme.surface2)))
        }
        .buttonStyle(PressStyle(scale: 0.97))
    }

    private func rowIcon(_ name: String) -> some View {
        Image(systemName: name)
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(Theme.crimson)
            .frame(width: 40, height: 40)
            .background(Circle().fill(Theme.crimson.opacity(0.14)))
    }

    private func time(_ s: Double) -> String {
        let t = Int(s)
        return t >= 3600 ? String(format: "%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60) : String(format: "%d:%02d", t / 60, t % 60)
    }
}
