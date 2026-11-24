import SwiftUI

/// One place decides which sheet is up, so every button that opens one works.
enum ActiveSheet: String, Identifiable {
    case settings, devices, inputs, pairing
    var id: String { rawValue }
}

struct ContentView: View {
    @EnvironmentObject var tv: TVController
    @Environment(\.scenePhase) private var phase
    @AppStorage("appearance") private var appearance = Appearance.system.rawValue
    @AppStorage("accent") private var accent = Accent.crimson.rawValue
    @State private var tab = 0
    @State private var sheet: ActiveSheet?
    @State private var appeared = false
    @State private var splash = true

    var body: some View {
        ZStack(alignment: .bottom) {
            Theme.backdrop.ignoresSafeArea()

            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    header
                    NowCard().padding(.top, 16)
                    TabBar(selection: $tab).padding(.top, 18)

                    Group {
                        switch tab {
                        case 0: RemotePad(openInputs: { sheet = .inputs })
                                .transition(.asymmetric(insertion: .move(edge: .leading).combined(with: .opacity), removal: .opacity))
                        case 1: TouchpadPanel()
                                .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
                        case 2: KeyboardPanel()
                                .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
                        default: CastPanel()
                                .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
                        }
                    }
                    .padding(.top, 18)
                    .opacity(tv.status == .connected ? 1 : 0.45)
                    .animation(.easeInOut(duration: 0.25), value: tv.status)

                    AppsRow().padding(.top, 24)

                    Text("Crimson Remote")
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.faint)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 24)
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 30)
                .opacity(appeared ? 1 : 0)
                .offset(y: appeared ? 0 : 18)
            }
            .scrollDismissesKeyboard(.interactively)

            if let message = tv.toast {
                Text(message)
                    .font(.system(size: 14, weight: .medium))
                    .padding(.horizontal, 18).padding(.vertical, 12)
                    .background(Capsule().fill(Theme.toast))
                    .foregroundStyle(.white)
                    .padding(.bottom, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if splash {
                SplashView()
                    .transition(.opacity.combined(with: .scale(scale: 1.04)))
                    .zIndex(10)
            }
        }
        .id(accent)   // re-draw everything when the accent colour changes
        .preferredColorScheme(Appearance(rawValue: appearance)?.scheme)
        .onAppear {
            tv.connect()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
                withAnimation(.easeInOut(duration: 0.45)) { splash = false }
                withAnimation(.easeOut(duration: 0.5).delay(0.15)) { appeared = true }
            }
        }
        .onChange(of: phase) { p in
            if p == .active { tv.connect() } else if p == .background { tv.disconnect() }
        }
        .onChange(of: tv.chooseTV) { choose in
            if choose { sheet = .devices; tv.chooseTV = false }
        }
        .onChange(of: tv.showPairing) { show in
            if show { sheet = .pairing } else if sheet == .pairing { sheet = nil }
        }
        .sheet(item: $sheet, onDismiss: { if tv.showPairing { tv.showPairing = false } }) { which in
            Group {
                switch which {
                case .settings: SettingsView(openDevices: { sheet = .devices })
                case .devices: DevicesView()
                case .inputs: InputSheet()
                case .pairing: PairingSheet().interactiveDismissDisabled()
                }
            }
            .presentationDetents(which == .pairing ? [.height(340)] : which == .inputs ? [.medium] : [.medium, .large])
            .presentationBackground(Theme.sheet)
            .preferredColorScheme(Appearance(rawValue: appearance)?.scheme)
            .environmentObject(tv)
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 10) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("CRIMSON")
                        .font(.system(size: 12, weight: .black))
                        .tracking(3.8)
                        .foregroundStyle(Theme.crimson)
                    Button { sheet = .devices } label: {
                        HStack(spacing: 6) {
                            Text(tv.displayName)
                                .font(.system(size: 24, weight: .heavy))
                                .foregroundStyle(Theme.text)
                                .lineLimit(1)
                                .minimumScaleFactor(0.7)
                            Image(systemName: "chevron.down")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(Theme.sub)
                        }
                    }
                    .buttonStyle(PressStyle(scale: 0.97))
                }
                Spacer()
                Button { sheet = .devices } label: {
                    Image(systemName: "tv")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.sub)
                        .frame(width: 40, height: 40)
                        .card(radius: 20)
                        .overlay(alignment: .topTrailing) {
                            if tv.devices.count > 1 {
                                Text("\(tv.devices.count)")
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundStyle(.white)
                                    .frame(width: 16, height: 16)
                                    .background(Circle().fill(Theme.crimson))
                                    .offset(x: 3, y: -3)
                            }
                        }
                }
                .buttonStyle(PressStyle())
                .accessibilityLabel("TVs")
                Button { sheet = .settings } label: {
                    Image(systemName: "gearshape")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.sub)
                        .frame(width: 40, height: 40)
                        .card(radius: 20)
                }
                .buttonStyle(PressStyle())
                .accessibilityLabel("Settings")
            }
            ConnectionCard(openTVs: { sheet = .devices }, openSettings: { sheet = .settings })
        }
    }
}

struct SplashView: View {
    @State private var reveal = false
    @State private var glow = false

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            Circle()
                .fill(RadialGradient(colors: [Theme.crimson.opacity(0.45), .clear], center: .center, startRadius: 0, endRadius: 220))
                .frame(width: 440, height: 440)
                .scaleEffect(glow ? 1.1 : 0.7)
                .opacity(glow ? 1 : 0)
            VStack(spacing: 18) {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .fill(Theme.crimsonGradient)
                    .frame(width: 84, height: 84)
                    .overlay(Image(systemName: "av.remote.fill").font(.system(size: 38)).foregroundStyle(.white))
                    .shadow(color: Theme.crimson.opacity(0.5), radius: 24, y: 10)
                    .scaleEffect(reveal ? 1 : 0.6)
                    .opacity(reveal ? 1 : 0)
                Text("CRIMSON")
                    .font(.system(size: 34, weight: .black))
                    .tracking(reveal ? 10 : 2)
                    .foregroundStyle(Theme.text)
                    .opacity(reveal ? 1 : 0)
                Text("Remote")
                    .font(.system(size: 14, weight: .medium))
                    .tracking(3)
                    .foregroundStyle(Theme.sub)
                    .opacity(reveal ? 1 : 0)
                    .offset(y: reveal ? 0 : 8)
            }
        }
        .onAppear {
            withAnimation(.spring(response: 0.7, dampingFraction: 0.7)) { reveal = true }
            withAnimation(.easeOut(duration: 1.2)) { glow = true }
        }
    }
}

/// What the connection is doing right now, with only the buttons that make sense.
struct ConnectionCard: View {
    @EnvironmentObject var tv: TVController
    var openTVs: () -> Void
    var openSettings: () -> Void
    @State private var breathe = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Circle().fill(color).frame(width: 10, height: 10)
                    .opacity(busy && breathe ? 0.25 : 1)
                    .animation(busy ? .easeInOut(duration: 0.7).repeatForever() : .default, value: breathe)
                VStack(alignment: .leading, spacing: 2) {
                    Text(head).font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.text)
                        .contentTransition(.opacity)
                    if !detail.isEmpty {
                        Text(detail).font(.system(size: 12)).foregroundStyle(Theme.sub).lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 8) {
                ForEach(buttons, id: \.0) { b in
                    Button { Haptics.tap(); b.2() } label: {
                        Text(b.0).font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(b.1 ? Color.white : Theme.text)
                            .frame(maxWidth: .infinity).frame(height: 38)
                            .background(Capsule().fill(b.1 ? AnyShapeStyle(Theme.crimsonGradient) : AnyShapeStyle(Theme.surface2)))
                            .overlay(Capsule().stroke(b.1 ? Color.clear : Theme.stroke))
                    }
                    .buttonStyle(PressStyle(scale: 0.95))
                }
            }
        }
        .padding(14)
        .card(radius: 18)
        .animation(.easeInOut(duration: 0.25), value: tv.status)
        .onAppear { breathe = true }
    }

    private var busy: Bool { [.connecting, .searching, .pairing, .waking].contains(tv.status) }
    private var color: Color {
        switch tv.status {
        case .connected: return Theme.green
        case .offline, .none: return Theme.red
        case .disconnected, .idle: return Theme.faint
        default: return Theme.amber
        }
    }
    private var name: String { tv.current?.title ?? "the TV" }
    private var head: String {
        switch tv.status {
        case .idle: return tv.current == nil ? "No TV yet" : "Ready to connect"
        case .searching: return "Looking for TVs on this Wi-Fi"
        case .connecting: return "Connecting to \(name)"
        case .connected: return "Connected"
        case .offline: return "\(name) isn't answering"
        case .pairing: return "Waiting for the TV"
        case .disconnected: return "Disconnected"
        case .none: return "No TVs found"
        case .choose: return "\(tv.devices.count) TVs found"
        case .waking: return "Turning on \(name)"
        }
    }
    private var detail: String {
        switch tv.status {
        case .connected: return [tv.current?.brand.label, tv.connectedIP].compactMap { $0 }.joined(separator: " · ")
        case .connecting: return tv.current?.ip ?? ""
        case .searching: return "This takes a few seconds"
        case .offline: return tv.current?.canWake == true ? "It may be off. Tap Turn on." : "It may be off, or on another Wi-Fi"
        case .pairing: return tv.statusNote ?? "Approve Crimson on the TV"
        case .disconnected: return "Tap Connect when you want to use the TV"
        case .none: return "Turn a TV on and keep this iPhone on the same Wi-Fi"
        case .choose: return "Choose which one to use"
        case .waking: return "Sent the wake-up signal"
        case .idle: return tv.current == nil ? "Find a TV on this Wi-Fi" : ""
        }
    }
    private var buttons: [(String, Bool, () -> Void)] {
        let canWake = tv.current?.canWake == true
        switch tv.status {
        case .connected: return [("Disconnect", false, tv.disconnectNow), ("Switch TV", false, openTVs)]
        case .connecting, .searching, .waking: return [("Stop", false, tv.disconnectNow)]
        case .pairing: return [("Stop", false, tv.disconnectNow), ("Try again", true, tv.reconnect)]
        case .offline:
            var b: [(String, Bool, () -> Void)] = []
            if canWake { b.append(("Turn on", true, tv.wake)) }
            b.append(("Reconnect", !canWake, tv.reconnect))
            b.append(("Switch TV", false, openTVs))
            return b
        case .choose: return [("Choose TV", true, openTVs), ("Search again", false, tv.reconnect)]
        case .none: return [("Search again", true, tv.reconnect), ("Add by address", false, openSettings)]
        case .disconnected, .idle:
            var b: [(String, Bool, () -> Void)] = [("Connect", true, tv.reconnect)]
            if canWake { b.append(("Turn on", false, tv.wake)) }
            b.append(("Switch TV", false, openTVs))
            return b
        }
    }
}

struct NowCard: View {
    @EnvironmentObject var tv: TVController

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("ON THE TV")
                .font(.system(size: 10, weight: .black))
                .tracking(2.2)
                .foregroundStyle(Theme.crimson)
            Text(tv.nowTitle)
                .font(.system(size: 20, weight: .heavy))
                .foregroundStyle(Theme.text)
                .lineLimit(1)
                .padding(.top, 8)
                .id(tv.nowTitle)
                .transition(.opacity.combined(with: .offset(y: 6)))
            Text(tv.nowSubtitle)
                .font(.system(size: 13))
                .foregroundStyle(Theme.sub)
                .padding(.top, 5)
                .id(tv.nowSubtitle)
                .transition(.opacity)
            HStack(spacing: 12) {
                Image(systemName: tv.volume == 0 ? "speaker.slash.fill" : "speaker.wave.2.fill")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.sub)
                    .frame(width: 20)
                Slider(value: Binding(get: { tv.volume }, set: { v in
                    tv.volume = v.rounded()
                    tv.setVolume(v)
                }), in: 0...100) { editing in
                    if !editing { tv.setVolume(tv.volume, force: true) } else { Haptics.tick() }
                }
                .tint(Theme.crimson)
                .disabled(!tv.canSetVolume)
                .opacity(tv.canSetVolume ? 1 : 0.4)
                Text("\(Int(tv.volume))")
                    .font(.system(size: 13, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.text)
                    .frame(width: 30, alignment: .trailing)
            }
            .padding(.top, 10)
        }
        .animation(.easeOut(duration: 0.3), value: tv.nowTitle)
        .padding(.horizontal, 18).padding(.top, 16).padding(.bottom, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            ZStack {
                RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Theme.surface)
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(LinearGradient(colors: [Theme.crimson.opacity(0.22), .clear], startPoint: .topLeading, endPoint: .bottomTrailing))
                Circle()
                    .fill(RadialGradient(colors: [Theme.crimson.opacity(0.2), .clear], center: .center, startRadius: 0, endRadius: 110))
                    .frame(width: 220, height: 220)
                    .offset(x: 120, y: -80)
            }
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        )
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(Theme.crimson.opacity(0.25), lineWidth: 1))
    }
}

struct TabBar: View {
    @Binding var selection: Int
    @Namespace private var ns
    private let names = ["Remote", "Touchpad", "Keys", "Cast"]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<names.count, id: \.self) { i in
                Button {
                    Haptics.tick()
                    withAnimation(.spring(response: 0.38, dampingFraction: 0.78)) { selection = i }
                } label: {
                    Text(names[i])
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(selection == i ? Color.white : Theme.sub)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background {
                            if selection == i {
                                Capsule().fill(Theme.crimsonGradient)
                                    .shadow(color: Theme.crimson.opacity(0.35), radius: 10, y: 6)
                                    .matchedGeometryEffect(id: "tab", in: ns)
                            }
                        }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(4)
        .frame(height: 46)
        .card(radius: 23)
    }
}

struct AppsRow: View {
    @EnvironmentObject var tv: TVController

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("APPS")
                .font(.system(size: 11, weight: .semibold))
                .tracking(2)
                .foregroundStyle(Theme.faint)
            if tv.apps.isEmpty {
                Text("Your TV's apps show here once it's connected.")
                    .font(.system(size: 13)).foregroundStyle(Theme.faint)
                    .padding(.vertical, 8)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(Array(tv.apps.enumerated()), id: \.element.id) { i, app in
                        AppTile(app: app, index: i)
                    }
                }
                .padding(.horizontal, 20)
            }
            .padding(.horizontal, -20)
        }
    }
}

struct AppTile: View {
    @EnvironmentObject var tv: TVController
    let app: TVApp
    let index: Int
    @State private var shown = false

    private static let tints: [Color] = [0xE11D48, 0xF97316, 0x8B5CF6, 0x06B6D4, 0x22C55E, 0xEAB308, 0x3B82F6, 0xEC4899].map { Color(hex: $0) }

    var body: some View {
        Button { tv.launch(app) } label: {
            VStack(spacing: 6) {
                ZStack {
                    let tint = Self.tints[app.displayName.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0x7FFFFFFF } % Self.tints.count]
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(LinearGradient(colors: [tint, tint.opacity(0.6)], startPoint: .topLeading, endPoint: .bottomTrailing))
                    Text(String(app.displayName.prefix(1)).uppercased())
                        .font(.system(size: 22, weight: .heavy))
                        .foregroundStyle(.white)
                    if let url = app.logoURL {
                        AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.3))) { phase in
                            if case .success(let img) = phase {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.white)
                                    img.resizable().interpolation(.high).scaledToFit().padding(11)
                                }
                                .transition(.opacity)
                            }
                        }
                    }
                }
                .frame(width: 58, height: 58)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .shadow(color: .black.opacity(0.08), radius: 6, y: 3)
                Text(app.displayName)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Theme.sub)
                    .lineLimit(1)
                    .frame(width: 70)
            }
            .padding(6)
        }
        .buttonStyle(PressStyle())
        .opacity(shown ? 1 : 0)
        .offset(y: shown ? 0 : 10)
        .onAppear {
            withAnimation(.easeOut(duration: 0.3).delay(Double(index) * 0.035)) { shown = true }
        }
    }
}
