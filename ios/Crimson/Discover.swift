import SwiftUI

/// The Crimson mark: a crimson C that sweeps in around a play symbol, with a soft glow.
struct CrimsonMark: View {
    var size: CGFloat = 96
    var animate = true
    @State private var sweep: CGFloat = 0
    @State private var play = false
    @State private var glow = false

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.24, style: .continuous)
                .fill(RadialGradient(colors: [Color(hex: 0x600A20), Color(hex: 0x0A0307)],
                                     center: UnitPoint(x: 0.32, y: 0.28), startRadius: 0, endRadius: size * 1.05))
            Circle()
                .trim(from: 0, to: sweep)
                .stroke(Theme.crimson, style: StrokeStyle(lineWidth: size * 0.11, lineCap: .round))
                .blur(radius: size * 0.06)
                .opacity(glow ? 0.9 : 0.5)
                .frame(width: size * 0.6, height: size * 0.6)
                .rotationEffect(.degrees(48))
            Circle()
                .trim(from: 0, to: sweep)
                .stroke(LinearGradient(colors: [Color(hex: 0xFF5C7A), Theme.crimsonDark], startPoint: .topLeading, endPoint: .bottomTrailing),
                        style: StrokeStyle(lineWidth: size * 0.11, lineCap: .round))
                .frame(width: size * 0.6, height: size * 0.6)
                .rotationEffect(.degrees(48))
            PlayShape()
                .fill(.white)
                .frame(width: size * 0.16, height: size * 0.19)
                .offset(x: size * 0.02)
                .scaleEffect(play ? 1 : 0.2)
                .opacity(play ? 1 : 0)
        }
        .frame(width: size, height: size)
        .shadow(color: Theme.crimson.opacity(glow ? 0.55 : 0.2), radius: size * 0.22, y: size * 0.06)
        .onAppear {
            guard animate else { sweep = 0.74; play = true; glow = true; return }
            withAnimation(.easeOut(duration: 0.9)) { sweep = 0.74 }
            withAnimation(.spring(response: 0.5, dampingFraction: 0.55).delay(0.55)) { play = true }
            withAnimation(.easeInOut(duration: 1.4).repeatForever(autoreverses: true).delay(0.9)) { glow = true }
        }
    }
}

struct PlayShape: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.minX, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX, y: r.midY))
        p.addLine(to: CGPoint(x: r.minX, y: r.maxY))
        p.closeSubpath()
        return p
    }
}

/// The TVs tab: finds TVs on this Wi-Fi, shows saved ones, connects with a tap.
struct DiscoverScreen: View {
    @EnvironmentObject var tv: TVController
    @State private var forgetting: SavedTV?
    @State private var pulse = false
    private let rescan = Timer.publish(every: 25, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            AppBackground()
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 22) {
                    ScreenHeader(title: "TVs")

                    // Radar
                    VStack(spacing: 14) {
                        ZStack {
                            ForEach(0..<3) { i in
                                Circle()
                                    .stroke(Theme.crimson.opacity(0.35), lineWidth: 1.5)
                                    .scaleEffect(pulse ? 1 : 0.25)
                                    .opacity(pulse ? 0 : 1)
                                    .animation(tv.scanning ? .easeOut(duration: 2.2).repeatForever(autoreverses: false).delay(Double(i) * 0.7) : .default,
                                               value: pulse)
                            }
                            Circle().fill(Theme.crimsonGradient).frame(width: 64, height: 64)
                                .overlay(Image(systemName: "dot.radiowaves.left.and.right").font(.system(size: 24, weight: .semibold)).foregroundStyle(.white))
                                .shadow(color: Theme.crimson.opacity(0.45), radius: 14, y: 6)
                        }
                        .frame(height: 170)
                        Text(tv.scanning ? "Looking for TVs on this Wi-Fi…" : "\(tv.devices.count) TV\(tv.devices.count == 1 ? "" : "s") found on this Wi-Fi")
                            .font(.system(size: 14, weight: .medium)).foregroundStyle(Theme.sub)
                            .contentTransition(.opacity)
                        Button { Haptics.tap(); Task { await tv.discover() } } label: {
                            Label("Scan again", systemImage: "arrow.clockwise")
                                .font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                                .padding(.horizontal, 20).frame(height: 42)
                                .background(Capsule().fill(Theme.crimsonGradient))
                        }
                        .buttonStyle(PressStyle(scale: 0.96))
                        .disabled(tv.scanning)
                        .opacity(tv.scanning ? 0.5 : 1)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 18)
                    .card(radius: 28)

                    if !tv.saved.isEmpty {
                        Text("YOUR TVS").sectionLabel()
                        VStack(spacing: 10) {
                            ForEach(tv.saved) { saved in
                                let here = found(for: saved)
                                let connected = saved.id == tv.currentID && tv.status == .connected
                                TVRow(name: saved.title,
                                      sub: [saved.brand.label, saved.model.isEmpty ? nil : saved.model].compactMap { $0 }.joined(separator: " \u{00B7} "),
                                      state: connected ? "Connected" : here != nil ? "Available" : tv.scanning ? "Checking…" : "Not found",
                                      stateColor: connected ? Theme.green : here != nil ? Theme.crimson : Theme.faint,
                                      brand: saved.brand, highlighted: connected,
                                      tap: { if let here { tv.select(here) } else { tv.select(saved: saved) } },
                                      forget: { forgetting = saved })
                            }
                        }
                    }

                    let fresh = tv.devices.filter { d in !tv.saved.contains { s in (!s.mac.isEmpty && s.mac == d.mac) || (s.mac.isEmpty && s.ip == d.ip) } }
                    if !fresh.isEmpty {
                        Text(tv.saved.isEmpty ? "FOUND ON THIS WI-FI" : "NEW TVS FOUND").sectionLabel()
                        VStack(spacing: 10) {
                            ForEach(fresh) { d in
                                TVRow(name: d.name, sub: [d.brand.label, d.model, d.ip].compactMap { $0 }.joined(separator: " \u{00B7} "),
                                      state: "Connect", stateColor: Theme.crimson, brand: d.brand, highlighted: false,
                                      tap: { tv.select(d) }, forget: nil)
                            }
                        }
                    }

                    Text("Crimson works with Hisense (VIDAA), Roku, Samsung (2016 and later) and LG (webOS) TVs on the same Wi-Fi.")
                        .font(.system(size: 12)).foregroundStyle(Theme.faint)
                        .padding(.top, 4)
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 30)
            }
        }
        .onAppear {
            pulse = true
            if !tv.scanning { Task { await tv.discover() } }
        }
        .onChange(of: tv.scanning) { s in if s { pulse = false; DispatchQueue.main.async { pulse = true } } }
        .onReceive(rescan) { _ in if !tv.scanning { Task { await tv.discover() } } }
        .alert(item: $forgetting) { t in
            Alert(title: Text("Forget \(t.title)?"),
                  message: Text("Crimson removes its saved address, MAC and pairing. You can add it again by connecting."),
                  primaryButton: .destructive(Text("Forget")) { tv.forget(t) },
                  secondaryButton: .cancel())
        }
    }

    private func found(for s: SavedTV) -> TVDevice? {
        if !s.mac.isEmpty, let d = tv.devices.first(where: { $0.mac == s.mac }) { return d }
        return s.mac.isEmpty ? tv.devices.first { $0.ip == s.ip } : nil
    }
}

struct TVRow: View {
    let name: String
    let sub: String
    let state: String
    let stateColor: Color
    let brand: TVBrand
    let highlighted: Bool
    let tap: () -> Void
    let forget: (() -> Void)?

    var body: some View {
        HStack(spacing: 12) {
            Button(action: { Haptics.tap(); tap() }) {
                HStack(spacing: 14) {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(highlighted ? AnyShapeStyle(Theme.crimsonGradient) : AnyShapeStyle(Theme.surface2))
                        .frame(width: 50, height: 50)
                        .overlay(Text(String(brand.label.prefix(1)))
                            .font(.system(size: 20, weight: .black))
                            .foregroundStyle(highlighted ? Color.white : Theme.sub))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(name).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.text).lineLimit(1)
                        Text(sub).font(.system(size: 12)).foregroundStyle(Theme.sub).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    Text(state)
                        .font(.system(size: 12, weight: .semibold)).foregroundStyle(stateColor)
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(Capsule().fill(stateColor.opacity(0.12)))
                }
            }
            .buttonStyle(PressStyle(scale: 0.98))
            if let forget {
                Button(action: forget) {
                    Image(systemName: "xmark").font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.faint)
                        .frame(width: 30, height: 30)
                }
                .accessibilityLabel("Forget this TV")
            }
        }
        .padding(12)
        .card(radius: 20)
    }
}

/// About Crimson Remote, read from the app itself.
struct AboutView: View {
    @EnvironmentObject var tv: TVController
    private var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?" }
    private var build: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?" }

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(spacing: 20) {
                CrimsonMark(size: 110)
                    .padding(.top, 10)
                VStack(spacing: 6) {
                    Text("Crimson Remote").font(.system(size: 26, weight: .heavy)).foregroundStyle(Theme.text)
                    Text("Version \(version) (\(build))").font(.system(size: 14, weight: .medium)).foregroundStyle(Theme.sub)
                    Text("Made by crimson").font(.system(size: 13)).foregroundStyle(Theme.faint)
                }

                VStack(spacing: 0) {
                    info("Connected TV", tv.status == .connected ? tv.displayName : "None")
                    if let cur = tv.current {
                        Divider().padding(.leading, 16)
                        info("Brand", cur.brand.label)
                        if !cur.model.isEmpty { Divider().padding(.leading, 16); info("Model", cur.model) }
                        Divider().padding(.leading, 16)
                        info("Address", cur.ip)
                        Divider().padding(.leading, 16)
                        info("Can turn on", cur.canWake ? "Yes" : "Not yet")
                    }
                    Divider().padding(.leading, 16)
                    info("Features", tv.features.isEmpty ? "—" : "\(tv.features.count) available")
                }
                .card(radius: 20)

                VStack(spacing: 0) {
                    link("Project on GitHub", "https://github.com/crimsonej/crimson-remote")
                    Divider().padding(.leading, 16)
                    link("Latest release", "https://github.com/crimsonej/crimson-remote/releases/latest")
                }
                .card(radius: 20)

                VStack(alignment: .leading, spacing: 8) {
                    Text("CREDITS").sectionLabel()
                    Text("LG pairing manifest from aiopylgtv (MIT). QR codes by Nayuki's QR Code generator (MIT).")
                        .font(.system(size: 12)).foregroundStyle(Theme.sub)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .card(radius: 20)
            }
            .padding(22)
        }
    }

    private func info(_ k: String, _ v: String) -> some View {
        HStack {
            Text(k).font(.system(size: 14)).foregroundStyle(Theme.sub)
            Spacer()
            Text(v).font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.text).lineLimit(1)
        }
        .padding(.horizontal, 16).padding(.vertical, 13)
    }

    private func link(_ title: String, _ url: String) -> some View {
        Link(destination: URL(string: url)!) {
            HStack {
                Text(title).font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.text)
                Spacer()
                Image(systemName: "arrow.up.right").font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.crimson)
            }
            .padding(.horizontal, 16).padding(.vertical, 14)
        }
    }
}
