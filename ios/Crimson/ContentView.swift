import SwiftUI

/// One place decides which sheet is up, so every button that opens one works.
enum ActiveSheet: String, Identifiable {
    case settings, devices, inputs, pairing, keyboard, more, speaker, about
    var id: String { rawValue }
}

final class SheetRouter: ObservableObject {
    @Published var sheet: ActiveSheet?
    @Published var tab = 0
}

struct ContentView: View {
    @EnvironmentObject var tv: TVController
    @Environment(\.scenePhase) private var phase
    @AppStorage("appearance") private var appearance = Appearance.system.rawValue
    @AppStorage("accent") private var accent = Accent.crimson.rawValue
    @AppStorage("style") private var style = ThemeStyle.glow.rawValue
    @StateObject private var router = SheetRouter()
    @State private var splash = true

    var body: some View {
        ZStack(alignment: .bottom) {
            TabView(selection: $router.tab) {
                RemoteScreen()
                    .tabItem { Label("Remote", systemImage: "av.remote") }
                    .tag(0)
                AppsScreen()
                    .tabItem { Label("Apps", systemImage: "square.grid.2x2.fill") }
                    .tag(1)
                CastScreen()
                    .tabItem { Label("Cast", systemImage: "play.tv.fill") }
                    .tag(2)
                DiscoverScreen()
                    .tabItem { Label("TVs", systemImage: "tv.and.mediabox") }
                    .tag(3)
            }
            .tint(Theme.crimson)

            if let message = tv.toast {
                Text(message)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18).padding(.vertical, 12)
                    .background(Capsule().fill(Color.black.opacity(0.82)))
                    .padding(.bottom, 96)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if splash {
                SplashView()
                    .transition(.opacity.combined(with: .scale(scale: 1.04)))
                    .zIndex(10)
            }
        }
        .environmentObject(router)
        .id("\(accent)-\(style)")   // redraw everything when the accent or style changes
        .preferredColorScheme(Appearance(rawValue: appearance)?.scheme)
        .onAppear {
            tv.connect()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) {
                withAnimation(.easeInOut(duration: 0.45)) { splash = false }
            }
        }
        .onChange(of: phase) { p in
            if p == .active { tv.connect() } else if p == .background { tv.disconnect() }
        }
        .onChange(of: tv.chooseTV) { choose in
            if choose { router.tab = 3; tv.chooseTV = false }
        }
        .onChange(of: tv.showPairing) { show in
            if show { router.sheet = .pairing } else if router.sheet == .pairing { router.sheet = nil }
        }
        .sheet(item: $router.sheet, onDismiss: { if tv.showPairing { tv.showPairing = false } }) { which in
            Group {
                switch which {
                case .settings: SettingsView(openDevices: { router.sheet = .devices })
                case .devices: DevicesView()
                case .inputs: InputSheet()
                case .pairing: PairingSheet().interactiveDismissDisabled()
                case .keyboard: KeyboardSheet()
                case .more: MoreSheet()
                case .speaker: SpeakerSheet()
                case .about: AboutView()
                }
            }
            .presentationDetents(detents(which))
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
            .presentationBackground(Theme.style == .glow ? AnyShapeStyle(.regularMaterial) : AnyShapeStyle(Theme.sheet))
            .preferredColorScheme(Appearance(rawValue: appearance)?.scheme)
            .environmentObject(tv)
            .environmentObject(router)
        }
    }

    private func detents(_ s: ActiveSheet) -> Set<PresentationDetent> {
        switch s {
        case .pairing: return [.height(340)]
        case .keyboard: return [.height(300)]
        case .speaker: return [.height(360)]
        case .inputs, .more: return [.medium, .large]
        default: return [.large]
        }
    }
}

/// The top of every screen: brand, the TV's name (tap to pick another TV), status and settings.
struct ScreenHeader: View {
    @EnvironmentObject var tv: TVController
    @EnvironmentObject var router: SheetRouter
    let title: String

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("CRIMSON \u{00B7} \(title.uppercased())")
                        .font(.system(size: 11, weight: .black))
                        .tracking(2.6)
                        .foregroundStyle(Theme.crimson)
                    Button { router.tab = 3 } label: {
                        HStack(spacing: 8) {
                            Circle().fill(statusColor).frame(width: 9, height: 9)
                            Text(tv.displayName)
                                .font(.system(size: 26, weight: .heavy))
                                .foregroundStyle(Theme.text)
                                .lineLimit(1)
                                .minimumScaleFactor(0.6)
                            Image(systemName: "chevron.down")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(Theme.sub)
                        }
                    }
                    .buttonStyle(PressStyle(scale: 0.97))
                    .accessibilityLabel("\(tv.displayName), \(tv.status == .connected ? "connected" : "not connected"). Choose TV")
                }
                Spacer()
                Button { router.sheet = .settings } label: {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Theme.sub)
                        .key(44)
                }
                .buttonStyle(PressStyle())
                .accessibilityLabel("Settings")
            }
            if tv.status != .connected {
                ConnectionBanner()
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: tv.status)
    }

    private var statusColor: Color {
        switch tv.status {
        case .connected: return Theme.green
        case .offline, .none: return Theme.red
        case .disconnected, .idle: return Theme.faint
        default: return Theme.amber
        }
    }
}

/// Shown only when the connection needs attention, with only the buttons that make sense.
struct ConnectionBanner: View {
    @EnvironmentObject var tv: TVController
    @EnvironmentObject var router: SheetRouter

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            ZStack {
                if busy {
                    ProgressView().tint(Theme.crimson)
                } else {
                    Image(systemName: icon).font(.system(size: 16, weight: .semibold)).foregroundStyle(iconColor)
                }
            }
            .frame(width: 34, height: 34)
            .background(Circle().fill(iconColor.opacity(0.14)))
            VStack(alignment: .leading, spacing: 2) {
                Text(head).font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.text).lineLimit(1)
                if !detail.isEmpty {
                    Text(detail).font(.system(size: 12)).foregroundStyle(Theme.sub).lineLimit(2)
                }
            }
            Spacer(minLength: 4)
            if let b = primary {
                Button { Haptics.tap(); b.1() } label: {
                    Text(b.0).font(.system(size: 13, weight: .semibold)).foregroundStyle(.white)
                        .padding(.horizontal, 14).frame(height: 34)
                        .background(Capsule().fill(Theme.crimsonGradient))
                }
                .buttonStyle(PressStyle(scale: 0.95))
            }
        }
        .padding(12)
        .card(radius: 20)
    }

    private var busy: Bool { [.connecting, .searching, .waking].contains(tv.status) }
    private var iconColor: Color {
        switch tv.status {
        case .offline, .none: return Theme.red
        case .pairing, .choose: return Theme.amber
        default: return Theme.sub
        }
    }
    private var icon: String {
        switch tv.status {
        case .offline: return "wifi.exclamationmark"
        case .none: return "tv.slash"
        case .pairing: return "lock.open.display"
        case .choose: return "tv.and.mediabox"
        default: return "pause.circle"
        }
    }
    private var name: String { tv.current?.title ?? "the TV" }
    private var head: String {
        switch tv.status {
        case .idle: return tv.current == nil ? "No TV yet" : "Not connected"
        case .searching: return "Looking for TVs"
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
        case .connecting: return tv.current?.ip ?? ""
        case .searching: return "On this Wi-Fi"
        case .offline: return tv.statusNote ?? (tv.current?.canWake == true ? "It may be off" : "It may be off, or on another Wi-Fi")
        case .pairing: return tv.statusNote ?? "Approve Crimson on the TV"
        case .none: return "Turn a TV on, same Wi-Fi as this iPhone"
        case .choose: return "Choose which one to use"
        default: return ""
        }
    }
    private var primary: (String, () -> Void)? {
        switch tv.status {
        case .offline: return tv.current?.canWake == true ? ("Turn on", tv.wake) : ("Retry", tv.reconnect)
        case .pairing: return ("Retry", tv.reconnect)
        case .disconnected, .idle: return ("Connect", tv.reconnect)
        case .none: return ("Search", tv.reconnect)
        case .choose: return ("Choose", { router.tab = 3 })
        case .connecting, .searching, .waking: return ("Stop", tv.disconnectNow)
        case .connected: return nil
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
                CrimsonMark(size: 108)
                    .scaleEffect(reveal ? 1 : 0.7)
                    .opacity(reveal ? 1 : 0)
                    .rotation3DEffect(.degrees(reveal ? 0 : 25), axis: (x: 1, y: 0, z: 0))
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

/// What's on the TV and its volume.
struct NowCard: View {
    @EnvironmentObject var tv: TVController

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("ON THE TV").sectionLabel()
                Text(tv.status == .connected ? tv.nowTitle : "Not connected")
                    .font(.system(size: 20, weight: .heavy))
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)
                    .id(tv.nowTitle)
                    .transition(.opacity.combined(with: .offset(y: 6)))
                if tv.status == .connected && !tv.nowSubtitle.trimmingCharacters(in: .whitespaces).isEmpty {
                    Text(tv.nowSubtitle).font(.system(size: 13)).foregroundStyle(Theme.sub).lineLimit(1)
                }
            }
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
                .disabled(!tv.canSetVolume || tv.status != .connected)
                Text("\(Int(tv.volume))")
                    .font(.system(size: 13, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.text)
                    .frame(width: 30, alignment: .trailing)
            }
        }
        .animation(.easeOut(duration: 0.3), value: tv.nowTitle)
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .card(radius: 24)
    }
}
