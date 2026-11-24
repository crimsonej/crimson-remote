import Foundation
import Network

/// A tiny MQTT 3.1.1 client (QoS 0) over TLS, for the Hisense VIDAA remote service.
final class MQTTClient {
    var onMessage: ((String, String) -> Void)?
    var onConnected: (() -> Void)?
    var onClosed: (() -> Void)?

    private let host: String
    private let clientId: String
    private let queue = DispatchQueue(label: "crimson.mqtt")
    private var connection: NWConnection?
    private var buffer = Data()
    private var pingTimer: DispatchSourceTimer?
    private var packetId: UInt16 = 1
    private(set) var isConnected = false
    private var closed = false

    init(host: String, clientId: String) {
        self.host = host
        self.clientId = clientId
    }

    func connect() {
        let tls = NWProtocolTLS.Options()
        // The TV uses a self-signed certificate, so accept it.
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, _, complete in
            complete(true)
        }, queue)
        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = 5
        let params = NWParameters(tls: tls, tcp: tcp)
        let conn = NWConnection(host: NWEndpoint.Host(host), port: 36669, using: params)
        connection = conn
        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.sendConnect()
                self.receive()
            case .failed, .cancelled:
                self.finish()
            case .waiting:
                // No route (TV off or wrong network): give up instead of waiting forever.
                self.close()
            default:
                break
            }
        }
        conn.start(queue: queue)
    }

    func close() {
        queue.async { [weak self] in
            self?.connection?.cancel()
            self?.finish()
        }
    }

    func subscribe(_ topic: String) {
        var body = Data()
        body.append(contentsOf: [UInt8(packetId >> 8), UInt8(packetId & 0xFF)])
        packetId &+= 1
        body.append(Self.str(topic))
        body.append(0)
        send(header: 0x82, body: body)
    }

    func publish(_ topic: String, _ payload: String = "") {
        var body = Self.str(topic)
        body.append(Data(payload.utf8))
        send(header: 0x30, body: body)
    }

    // MARK: - internals

    private func finish() {
        guard !closed else { return }
        closed = true
        isConnected = false
        pingTimer?.cancel()
        pingTimer = nil
        onClosed?()
    }

    private func sendConnect() {
        var body = Self.str("MQTT")
        body.append(contentsOf: [4, 0xC2, 0, 30])   // v3.1.1, user+pass+clean session, 30 s keepalive
        body.append(Self.str(clientId))
        body.append(Self.str("hisenseservice"))
        body.append(Self.str("multimqttservice"))
        send(header: 0x10, body: body)
    }

    private func send(header: UInt8, body: Data) {
        var packet = Data([header])
        var len = body.count
        repeat {
            var byte = UInt8(len % 128)
            len /= 128
            if len > 0 { byte |= 0x80 }
            packet.append(byte)
        } while len > 0
        packet.append(body)
        connection?.send(content: packet, completion: .contentProcessed { _ in })
    }

    private func receive() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
                self.parse()
            }
            if isComplete || error != nil {
                self.connection?.cancel()
                self.finish()
                return
            }
            self.receive()
        }
    }

    private func parse() {
        while true {
            let bytes = [UInt8](buffer)
            guard bytes.count >= 2 else { return }
            var multiplier = 1, length = 0, index = 1
            var complete = false
            while index < bytes.count && index <= 4 {
                let b = Int(bytes[index])
                length += (b & 127) * multiplier
                multiplier *= 128
                index += 1
                if b & 128 == 0 { complete = true; break }
            }
            guard complete, bytes.count >= index + length else { return }
            let header = bytes[0]
            let body = Array(bytes[index..<(index + length)])
            buffer = Data(bytes[(index + length)...])
            handle(header: header, body: body)
        }
    }

    private func handle(header: UInt8, body: [UInt8]) {
        switch header >> 4 {
        case 2: // CONNACK
            if body.count >= 2 && body[1] == 0 {
                isConnected = true
                startPing()
                onConnected?()
            } else {
                connection?.cancel()
                finish()
            }
        case 3: // PUBLISH
            guard body.count >= 2 else { return }
            let topicLength = Int(body[0]) << 8 | Int(body[1])
            guard body.count >= 2 + topicLength else { return }
            let topic = String(decoding: body[2..<(2 + topicLength)], as: UTF8.self)
            var offset = 2 + topicLength
            if (header >> 1) & 3 > 0 { offset += 2 }
            let payload = offset <= body.count ? String(decoding: body[offset...], as: UTF8.self) : ""
            onMessage?(topic, payload)
        default:
            break
        }
    }

    private func startPing() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 20, repeating: 20)
        timer.setEventHandler { [weak self] in self?.send(header: 0xC0, body: Data()) }
        timer.resume()
        pingTimer = timer
    }

    private static func str(_ s: String) -> Data {
        let d = Data(s.utf8)
        var out = Data([UInt8(d.count >> 8), UInt8(d.count & 0xFF)])
        out.append(d)
        return out
    }
}
