import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var tv: TVController
    @EnvironmentObject var router: SheetRouter
    @Environment(\.dismiss) private var dismiss
    var openDevices: () -> Void
    @AppStorage("appearance") private var appearance = Appearance.system.rawValue
    @AppStorage("accent") private var accent = Accent.crimson.rawValue
    @AppStorage("style") private var style = ThemeStyle.glow.rawValue
    @AppStorage("haptics") private var haptics = true
    @State private var address = ""

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
                                Text(tv.status == .connected ? "Connected · \(tv.connectedIP ?? "")" : "Not connected")
                                    .font(.system(size: 12)).foregroundStyle(Theme.sub)
                            }
                            Spacer()
                            Text("\(tv.saved.count) saved").font(.system(size: 12)).foregroundStyle(Theme.faint)
                            Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.faint)
                        }
                        .padding(14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressStyle(scale: 0.98))
                    if let cur = tv.current {
                        divider
                        Text("Saved: \(cur.brand.label) · \(cur.ip) · " + (cur.canWake ? "MAC \(cur.mac)" : "MAC not known yet"))
                            .font(.system(size: 12)).foregroundStyle(Theme.sub)
                            .padding(.horizontal, 14).padding(.vertical, 10)
                    }
                    divider
                    Text("Add a TV by address (only if it isn't found automatically)")
                        .font(.system(size: 12)).foregroundStyle(Theme.faint)
                        .padding(.horizontal, 14).padding(.top, 10)
                    field("e.g. 192.168.1.20", text: $address, keyboard: .decimalPad)
                    Button {
                        Haptics.tap()
                        let a = address.trimmingCharacters(in: .whitespaces)
                        guard a.split(separator: ".").count == 4 else { tv.show("Type an address like 192.168.1.20"); return }
                        tv.add(address: a)
                        dismiss()
                    } label: {
                        Text("Add and connect").font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
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
                        Text("Style").font(.system(size: 13, weight: .medium)).foregroundStyle(Theme.sub)
                        ForEach(ThemeStyle.allCases) { st in
                            Button {
                                Haptics.tick()
                                style = st.rawValue
                            } label: {
                                HStack(spacing: 12) {
                                    stylePreview(st)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(st.label).font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                                        Text(st.blurb).font(.system(size: 12)).foregroundStyle(Theme.sub)
                                    }
                                    Spacer()
                                    Image(systemName: style == st.rawValue ? "checkmark.circle.fill" : "circle")
                                        .font(.system(size: 20))
                                        .foregroundStyle(style == st.rawValue ? Theme.crimson : Theme.faint)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(PressStyle(scale: 0.98))
                        }
                    }
                    .padding(14)
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
                    Button { Haptics.tap(); router.sheet = .about } label: {
                        HStack(spacing: 12) {
                            CrimsonMark(size: 40, animate: false)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Crimson Remote").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                                Text("Version \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?") \u{00B7} by crimson")
                                    .font(.system(size: 12)).foregroundStyle(Theme.sub)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.faint)
                        }
                        .padding(14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressStyle(scale: 0.98))
                }
            }
            .padding(22)
        }

    }

    /// A tiny swatch that shows what each style looks like.
    private func stylePreview(_ st: ThemeStyle) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(st == .oled ? Color.black : st == .solid ? Color(hex: 0x17171E) : Color(hex: 0x1A0B11))
            if st == .glow {
                Circle().fill(Theme.crimson.opacity(0.7)).frame(width: 26).blur(radius: 8).offset(x: -8, y: -6)
                Circle().fill(Theme.partner.opacity(0.6)).frame(width: 22).blur(radius: 8).offset(x: 10, y: 8)
            }
            RoundedRectangle(cornerRadius: 5).fill(Color.white.opacity(st == .glow ? 0.18 : 0.10)).frame(width: 26, height: 14)
        }
        .frame(width: 44, height: 44)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
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
    @State private var forgetting: SavedTV?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("TVs").font(.system(size: 26, weight: .heavy)).foregroundStyle(Theme.text)
                    Text(tv.scanning ? "Searching this Wi-Fi…" : "\(tv.devices.count) found on this Wi-Fi right now")
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

            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 10) {
                    if !tv.saved.isEmpty {
                        label("YOUR TVS")
                        ForEach(tv.saved) { saved in
                            let here = found(for: saved)
                            let isCurrent = saved.id == tv.currentID
                            let connected = isCurrent && tv.status == .connected
                            row(name: saved.title,
                                sub: saved.brand.label + (saved.model.isEmpty ? "" : " · \(saved.model)") + (saved.canWake ? "" : " · can't be turned on yet"),
                                state: connected ? "Connected" : here != nil ? "On · \(here!.ip)" : tv.scanning ? "Checking…" : "Not found",
                                connected: connected,
                                tap: {
                                    if let here { tv.select(here) } else { tv.select(saved: saved) }
                                    if !connected { dismiss() }
                                },
                                forget: { forgetting = saved })
                        }
                    }
                    let fresh = tv.devices.filter { d in !tv.saved.contains { s in (!s.mac.isEmpty && s.mac == d.mac) || (s.mac.isEmpty && s.ip == d.ip) } }
                    if !fresh.isEmpty {
                        label(tv.saved.isEmpty ? "FOUND ON THIS WI-FI" : "OTHER TVS FOUND").padding(.top, 6)
                        ForEach(fresh) { d in
                            row(name: d.name, sub: [d.brand.label, d.model, d.ip].compactMap { $0 }.joined(separator: " · "),
                                state: "Connect", connected: false,
                                tap: { tv.select(d); dismiss() }, forget: nil)
                        }
                    }
                    if tv.saved.isEmpty && fresh.isEmpty && !tv.scanning {
                        VStack(spacing: 10) {
                            Image(systemName: "tv.slash").font(.system(size: 34)).foregroundStyle(Theme.faint)
                            Text("No TVs found").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                            Text("Turn the TV on and keep this iPhone on the same Wi-Fi, then tap refresh.")
                                .font(.system(size: 13)).foregroundStyle(Theme.sub).multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity).padding(.vertical, 30)
                    }
                }
            }
        }
        .padding(22)
        .task { if tv.devices.isEmpty && !tv.scanning { await tv.discover() } }
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

    private func label(_ t: String) -> some View {
        Text(t).font(.system(size: 11, weight: .bold)).tracking(1.6).foregroundStyle(Theme.faint).padding(.leading, 4)
    }

    private func row(name: String, sub: String, state: String, connected: Bool, tap: @escaping () -> Void, forget: (() -> Void)?) -> some View {
        HStack(spacing: 12) {
            Button(action: tap) {
                HStack(spacing: 14) {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(connected ? AnyShapeStyle(Theme.crimsonGradient) : AnyShapeStyle(Theme.surface2))
                        .frame(width: 46, height: 46)
                        .overlay(Image(systemName: "tv.fill").foregroundStyle(connected ? Color.white : Theme.sub))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(name).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.text)
                        Text(sub).font(.system(size: 12)).foregroundStyle(Theme.sub).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    let c = connected ? Theme.green : (state.hasPrefix("On") || state == "Connect") ? Theme.crimson : Theme.faint
                    Text(state)
                        .font(.system(size: 12, weight: .semibold)).foregroundStyle(c)
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(Capsule().fill(c.opacity(0.12)))
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
        .card(radius: 18)
    }
}
