import Foundation
import Network
import SwiftUI
import PhotosUI
import AVKit
import UniformTypeIdentifiers

/// Casts photos and videos to the TV with DLNA: the iPhone serves the file on a
/// small web server and tells the TV's media renderer to play that address.
@MainActor
final class Caster: ObservableObject {
    @Published var status = "Nothing casting"
    @Published var busy = false

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "crimson.cast")
    private var fileURL: URL?
    private var mime = "application/octet-stream"
    private var controlURL: URL?
    private let port: UInt16 = 8790

    func cast(_ file: URL, mime: String, tvIP: String) async {
        busy = true
        defer { busy = false }
        status = "Sending to the TV…"
        do {
            guard let phone = TVController.wifiAddress() else { throw CastError("Connect the iPhone to Wi-Fi first") }
            try await prepare(tvIP)
            try startServer()
            fileURL = file
            self.mime = mime
            let ext = file.pathExtension.isEmpty ? "bin" : file.pathExtension
            let url = "http://\(phone):\(port)/media/\(Int(Date().timeIntervalSince1970)).\(ext)"
            _ = try? await soap("Stop", "<InstanceID>0</InstanceID>")
            _ = try await soap("SetAVTransportURI",
                               "<InstanceID>0</InstanceID><CurrentURI>\(esc(url))</CurrentURI><CurrentURIMetaData>\(esc(didl(url, file.lastPathComponent)))</CurrentURIMetaData>")
            _ = try await soap("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            status = "Casting \(mime.hasPrefix("image") ? "photo" : mime.hasPrefix("audio") ? "music" : "video")"
        } catch {
            status = (error as? CastError)?.message ?? "Couldn't cast: \(error.localizedDescription)"
        }
    }

    func control(_ action: String) async {
        do {
            switch action {
            case "play": _ = try await soap("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            case "pause": _ = try await soap("Pause", "<InstanceID>0</InstanceID>")
            default:
                _ = try await soap("Stop", "<InstanceID>0</InstanceID>")
                status = "Nothing casting"
            }
        } catch {
            status = "Choose something to cast first"
        }
    }

    // MARK: - UPnP

    private func prepare(_ tvIP: String) async throws {
        if let c = controlURL, c.host == tvIP { return }
        let base = "http://\(tvIP):38400"
        guard let descURL = URL(string: base + "/MediaServer/rendererdevicedesc.xml") else { throw CastError("Bad TV address") }
        var req = URLRequest(url: descURL)
        req.timeoutInterval = 4
        guard let (data, _) = try? await URLSession.shared.data(for: req) else { throw CastError("This TV doesn't accept casting right now") }
        let xml = String(decoding: data, as: UTF8.self)
        guard let svc = xml.range(of: ":service:AVTransport:"),
              let a = xml.range(of: "<controlURL>", range: svc.upperBound..<xml.endIndex),
              let b = xml.range(of: "</controlURL>", range: a.upperBound..<xml.endIndex) else {
            throw CastError("This TV doesn't accept casting")
        }
        let path = xml[a.upperBound..<b.lowerBound].trimmingCharacters(in: .whitespaces)
        controlURL = URL(string: path.hasPrefix("http") ? path : base + (path.hasPrefix("/") ? "" : "/") + path)
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
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"1\" parentID=\"0\" restricted=\"1\"><dc:title>\(esc(title))</dc:title><upnp:class>\(cls)</upnp:class><res protocolInfo=\"http-get:*:\(mime):*\">\(esc(url))</res></item></DIDL-Lite>"
    }

    private func esc(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
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
                Self.pump(conn, handle, remaining: length)
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

struct CastPanel: View {
    @EnvironmentObject var tv: TVController
    @StateObject private var caster = Caster()
    @ObservedObject private var desktop = DesktopModel.shared
    @State private var item: PhotosPickerItem?
    @State private var showDesktop = false

    var body: some View {
        VStack(spacing: 14) {
            card(icon: "play.rectangle.fill", title: "Photos & videos",
                 text: "Send a photo or video from this iPhone to the TV over Wi-Fi.") {
                PhotosPicker(selection: $item, matching: .any(of: [.images, .videos])) {
                    actionLabel(caster.busy ? "Sending…" : "Choose from Photos")
                }
                .buttonStyle(PressStyle(scale: 0.97))
                .disabled(caster.busy)
            }
            HStack(spacing: 8) {
                control("Play", "play")
                control("Pause", "pause")
                control("Stop", "stop")
            }
            Text(caster.status)
                .font(.system(size: 12)).foregroundStyle(Theme.faint)
                .frame(maxWidth: .infinity)
            card(icon: "airplayvideo", title: "Mirror this iPhone",
                 text: "Mirroring uses AirPlay. It works only if your TV lists AirPlay; this Hisense uses Screen Share (Miracast), which iPhones can't send to.") {
                HStack {
                    Text("AirPlay receivers").font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.text)
                    Spacer()
                    AirPlayButton().frame(width: 44, height: 44)
                }
                .padding(.horizontal, 14)
                .frame(height: 44)
                .card(radius: 22, fill: Theme.surface2)
            }
            card(icon: "desktopcomputer", title: "Crimson Desktop",
                 text: desktop.connected ? "The TV is showing the Crimson desktop. Open the controls to use your iPhone as its trackpad and keyboard."
                     : "Connect the iPhone to the TV with AirPlay or an HDMI adapter cable, and the TV shows a desktop with a web browser.") {
                Button { Haptics.tap(); showDesktop = true } label: {
                    actionLabel(desktop.connected ? "Open desktop controls" : "Open controls")
                }
                .buttonStyle(PressStyle(scale: 0.97))
            }
        }
        .fullScreenCover(isPresented: $showDesktop) {
            DesktopControls()
                .environmentObject(DesktopModel.shared)
                .background(Theme.bg.ignoresSafeArea())
        }
        .onChange(of: item) { newItem in
            guard let newItem else { return }
            Task { await send(newItem) }
        }
    }

    private func send(_ item: PhotosPickerItem) async {
        guard let ip = tv.connectedIP ?? (tv.ip.isEmpty ? nil : tv.ip) else {
            caster.status = "Connect to a TV first"
            return
        }
        let isVideo = item.supportedContentTypes.contains { $0.conforms(to: .movie) }
        if isVideo, let movie = try? await item.loadTransferable(type: PickedMovie.self) {
            let type = UTType(filenameExtension: movie.url.pathExtension)?.preferredMIMEType ?? "video/mp4"
            await caster.cast(movie.url, mime: type, tvIP: ip)
        } else if let data = try? await item.loadTransferable(type: Data.self),
                  let image = UIImage(data: data), let jpeg = image.jpegData(compressionQuality: 0.9) {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("cast-\(Int(Date().timeIntervalSince1970)).jpg")
            try? jpeg.write(to: url)
            await caster.cast(url, mime: "image/jpeg", tvIP: ip)
        } else {
            caster.status = "Couldn't read that item"
        }
        self.item = nil
    }

    private func control(_ title: String, _ action: String) -> some View {
        Button { Haptics.tap(); Task { await caster.control(action) } } label: {
            Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.text)
                .frame(maxWidth: .infinity).frame(height: 46)
                .card(radius: 23, fill: Theme.surface2)
        }
        .buttonStyle(PressStyle())
    }

    private func actionLabel(_ t: String) -> some View {
        Text(t).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
            .frame(maxWidth: .infinity).frame(height: 44)
            .background(Capsule().fill(Theme.crimsonGradient))
    }

    private func card<Content: View>(icon: String, title: String, text: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 14) {
                RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.crimson.opacity(0.15))
                    .frame(width: 44, height: 44)
                    .overlay(Image(systemName: icon).font(.system(size: 18, weight: .semibold)).foregroundStyle(Theme.crimson))
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.text)
                    Text(text).font(.system(size: 12)).foregroundStyle(Theme.sub).fixedSize(horizontal: false, vertical: true)
                }
            }
            content()
        }
        .padding(16)
        .card(radius: 20)
    }
}
