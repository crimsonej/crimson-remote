import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var tv: TVController
    @Environment(\.dismiss) private var dismiss
    var openDevices: () -> Void
    @AppStorage("appearance") private var appearance = Appearance.system.rawValue
    @AppStorage("accent") private var accent = Accent.crimson.rawValue
    @AppStorage("haptics") private var haptics = true
    @State private var ip = ""
    @State private var mac = ""

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 22) {
                HStack {
                    Text("Settings").font(.system(size: 26, weight: .heavy)).foregroundStyle(Theme.text)
                    Spacer()
                    Button { dismiss() } label: {
                        Image(systemName: "xmark").font(.system(size: 14, weight: .bold))
                            .foregroundStyle(Theme.sub)
                            .frame(width: 34, height: 34)
                            .card(radius: 17)
                    }
                    .buttonStyle(PressStyle())
                }

                section("TV") {
                    Button(action: openDevices) {
                        HStack(spacing: 12) {
                            icon("tv", Theme.crimson)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(tv.displayName).font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                                Text(tv.connectedIP.map { "Connected · \($0)" } ?? "Not connected")
                                    .font(.system(size: 12)).foregroundStyle(Theme.sub)
                            }
                            Spacer()
                            Text("\(tv.devices.count) found").font(.system(size: 12)).foregroundStyle(Theme.faint)
                            Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.faint)
                        }
                        .padding(14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressStyle(scale: 0.98))
                    divider
                    field("TV address (found automatically)", text: $ip, keyboard: .decimalPad)
                    divider
                    field("TV MAC address, for power-on", text: $mac, keyboard: .asciiCapable)
                    Button {
                        Haptics.tap()
                        tv.saveSettings(ip: ip, mac: mac)
                        tv.show("Saved")
                    } label: {
                        Text("Save TV details").font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                            .frame(maxWidth: .infinity).frame(height: 44)
                            .background(Capsule().fill(Theme.crimsonGradient))
                    }
                    .buttonStyle(PressStyle(scale: 0.97))
                    .padding(14)
                }

                section("Appearance") {
                    HStack(spacing: 6) {
                        ForEach(Appearance.allCases) { a in
                            Button {
                                Haptics.tick()
                                withAnimation(.easeInOut(duration: 0.3)) { appearance = a.rawValue }
                            } label: {
                                VStack(spacing: 6) {
                                    Image(systemName: a == .system ? "circle.lefthalf.filled" : a == .light ? "sun.max.fill" : "moon.fill")
                                        .font(.system(size: 18))
                                    Text(a.label).font(.system(size: 12, weight: .semibold))
                                }
                                .foregroundStyle(appearance == a.rawValue ? Color.white : Theme.text)
                                .frame(maxWidth: .infinity).frame(height: 66)
                                .background(RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .fill(appearance == a.rawValue ? AnyShapeStyle(Theme.crimsonGradient) : AnyShapeStyle(Theme.surface2)))
                            }
                            .buttonStyle(PressStyle())
                        }
                    }
                    .padding(12)
                    divider
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Accent colour").font(.system(size: 13, weight: .medium)).foregroundStyle(Theme.sub)
                        HStack(spacing: 12) {
                            ForEach(Accent.allCases) { a in
                                Button {
                                    Haptics.tick()
                                    accent = a.rawValue
                                } label: {
                                    Circle()
                                        .fill(LinearGradient(colors: [Color(hex: a.main), Color(hex: a.deep)], startPoint: .topLeading, endPoint: .bottomTrailing))
                                        .frame(width: 36, height: 36)
                                        .overlay(Circle().stroke(Theme.text, lineWidth: accent == a.rawValue ? 2.5 : 0).padding(-4))
                                        .overlay(Image(systemName: "checkmark").font(.system(size: 13, weight: .bold))
                                            .foregroundStyle(.white).opacity(accent == a.rawValue ? 1 : 0))
                                }
                                .buttonStyle(PressStyle())
                                .accessibilityLabel(a.label)
                            }
                        }
                    }
                    .padding(14)
                }

                section("Feel") {
                    Toggle(isOn: $haptics) {
                        HStack(spacing: 12) {
                            icon("hand.tap.fill", Theme.crimson)
                            Text("Vibrate on taps").font(.system(size: 15, weight: .medium)).foregroundStyle(Theme.text)
                        }
                    }
                    .tint(Theme.crimson)
                    .padding(14)
                }

                section("About") {
                    HStack(spacing: 12) {
                        RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Theme.crimsonGradient)
                            .frame(width: 34, height: 34)
                            .overlay(Image(systemName: "av.remote.fill").foregroundStyle(.white).font(.system(size: 15)))
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Crimson Remote").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                            Text("Version \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0") · by crimson")
                                .font(.system(size: 12)).foregroundStyle(Theme.sub)
                        }
                    }
                    .padding(14)
                }
            }
            .padding(22)
        }
        .onAppear { ip = tv.ip; mac = tv.mac }
    }

    private var divider: some View { Rectangle().fill(Theme.stroke).frame(height: 1).padding(.leading, 14) }

    private func icon(_ name: String, _ color: Color) -> some View {
        RoundedRectangle(cornerRadius: 9, style: .continuous).fill(color.opacity(0.15))
            .frame(width: 34, height: 34)
            .overlay(Image(systemName: name).font(.system(size: 15, weight: .semibold)).foregroundStyle(color))
    }

    private func section<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased()).font(.system(size: 11, weight: .bold)).tracking(1.6).foregroundStyle(Theme.faint)
                .padding(.leading, 4)
            VStack(alignment: .leading, spacing: 0) { content() }
                .card(radius: 18)
        }
    }

    private func field(_ placeholder: String, text: Binding<String>, keyboard: UIKeyboardType) -> some View {
        TextField("", text: text, prompt: Text(placeholder).foregroundColor(Theme.faint))
            .keyboardType(keyboard)
            .textInputAutocapitalization(.characters)
            .autocorrectionDisabled()
            .foregroundStyle(Theme.text)
            .padding(14)
    }
}

struct DevicesView: View {
    @EnvironmentObject var tv: TVController
    @Environment(\.dismiss) private var dismiss
    @State private var spin = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("TVs").font(.system(size: 26, weight: .heavy)).foregroundStyle(Theme.text)
                    Text(tv.scanning ? "Searching this Wi-Fi…" : "\(tv.devices.count) found on this Wi-Fi")
                        .font(.system(size: 13)).foregroundStyle(Theme.sub)
                        .contentTransition(.opacity)
                }
                Spacer()
                Button {
                    Haptics.tap()
                    Task { await tv.discover() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Theme.crimson)
                        .rotationEffect(.degrees(spin ? 360 : 0))
                        .animation(tv.scanning ? .linear(duration: 0.9).repeatForever(autoreverses: false) : .default, value: spin)
                        .frame(width: 40, height: 40)
                        .card(radius: 20)
                }
                .buttonStyle(PressStyle())
                .disabled(tv.scanning)
                .onChange(of: tv.scanning) { s in spin = s }
            }

            if tv.devices.isEmpty && !tv.scanning {
                VStack(spacing: 10) {
                    Image(systemName: "tv.slash").font(.system(size: 34)).foregroundStyle(Theme.faint)
                    Text("No TVs found").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                    Text("Turn the TV on and keep this iPhone on the same Wi-Fi, then tap refresh.")
                        .font(.system(size: 13)).foregroundStyle(Theme.sub).multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity).padding(.vertical, 30)
            }

            ScrollView(showsIndicators: false) {
                VStack(spacing: 10) {
                    ForEach(tv.devices) { d in
                        let connected = d.ip == tv.connectedIP
                        Button { tv.select(d); if !connected { dismiss() } } label: {
                            HStack(spacing: 14) {
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .fill(connected ? AnyShapeStyle(Theme.crimsonGradient) : AnyShapeStyle(Theme.surface2))
                                    .frame(width: 46, height: 46)
                                    .overlay(Image(systemName: "tv.fill").foregroundStyle(connected ? Color.white : Theme.sub))
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(d.name).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.text)
                                    Text([d.model, d.ip].compactMap { $0 }.joined(separator: " · "))
                                        .font(.system(size: 12)).foregroundStyle(Theme.sub)
                                }
                                Spacer()
                                Text(connected ? "Connected" : "Connect")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(connected ? Theme.green : Theme.crimson)
                                    .padding(.horizontal, 10).padding(.vertical, 6)
                                    .background(Capsule().fill((connected ? Theme.green : Theme.crimson).opacity(0.12)))
                            }
                            .padding(12)
                            .card(radius: 18)
                        }
                        .buttonStyle(PressStyle(scale: 0.98))
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                    }
                }
            }
        }
        .padding(22)
        .task { if tv.devices.isEmpty { await tv.discover() } }
    }
}
