import SwiftUI

// MARK: - Remote screen

struct RemoteScreen: View {
    @EnvironmentObject var tv: TVController
    @EnvironmentObject var router: SheetRouter
    @AppStorage("padMode") private var touchpad = false
    @State private var playing = true

    /// The connected TV's features, or its brand's while it reconnects.
    private func has(_ f: Feature) -> Bool {
        tv.features.isEmpty ? (tv.current?.brand.features ?? TVBrand.hisense.features).contains(f) : tv.features.contains(f)
    }

    var body: some View {
        ZStack {
            AppBackground()
            ScrollView(showsIndicators: false) {
                VStack(spacing: 22) {
                    ScreenHeader(title: "Remote")
                    NowCard()

                    VStack(spacing: 26) {
                        topRow
                        modeSwitch
                        if touchpad {
                            TouchpadSurface()
                                .frame(height: 250)
                                .transition(.opacity.combined(with: .scale(scale: 0.96)))
                        } else {
                            HStack {
                                Rocker(label: "VOL", up: "KEY_VOLUMEUP", down: "KEY_VOLUMEDOWN")
                                Spacer()
                                DPad()
                                Spacer()
                                if has(.channels) {
                                    Rocker(label: "CH", up: "KEY_CHANNELUP", down: "KEY_CHANNELDOWN")
                                } else {
                                    Color.clear.frame(width: 56, height: 172)
                                }
                            }
                            .transition(.opacity.combined(with: .scale(scale: 0.96)))
                        }
                        navRow
                        if has(.media) { mediaRow }
                    }
                    .padding(.vertical, 22)
                    .padding(.horizontal, 16)
                    .card(radius: 30)
                    .opacity(tv.status == .connected ? 1 : 0.5)
                    .allowsHitTesting(tv.status == .connected || tv.current?.canWake == true)
                    .animation(.spring(response: 0.35, dampingFraction: 0.85), value: touchpad)

                    if has(.audioOnly) || has(.speakerVolume) || has(.tvSettings) {
                        TVFeaturesCard(audioOnly: has(.audioOnly), speaker: has(.speakerVolume), settings: has(.tvSettings))
                            .opacity(tv.status == .connected ? 1 : 0.5)
                            .allowsHitTesting(tv.status == .connected)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 30)
            }
        }
    }

    private var topRow: some View {
        HStack {
            Button { tv.power() } label: {
                Image(systemName: "power")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 58, height: 58)
                    .background(Circle().fill(Theme.crimsonGradient))
                    .shadow(color: Theme.crimson.opacity(0.4), radius: 12, y: 6)
            }
            .buttonStyle(PressStyle())
            .accessibilityLabel("Power")
            Spacer()
            if has(.inputList) {
                round("rectangle.on.rectangle", "Input") { Haptics.tap(); router.sheet = .inputs }
                Spacer()
            } else if has(.sourceKey) {
                round("rectangle.on.rectangle", "Source") { tv.key("KEY_SOURCE") }
                Spacer()
            }
            round("speaker.slash.fill", "Mute") { tv.key("KEY_MUTE") }
            if has(.keyboard) {
                Spacer()
                round("keyboard", "Keyboard") { Haptics.tap(); router.sheet = .keyboard }
            }
        }
    }

    private var modeSwitch: some View {
        HStack(spacing: 4) {
            mode("D-pad", "dpad.fill", !touchpad) { touchpad = false }
            mode("Touchpad", "hand.point.up.left.fill", touchpad) { touchpad = true }
        }
        .padding(4)
        .background(Capsule().fill(Theme.surface2.opacity(0.7)))
    }

    private func mode(_ t: String, _ icon: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button { Haptics.tick(); action() } label: {
            Label(t, systemImage: icon)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(on ? Color.white : Theme.sub)
                .padding(.horizontal, 16).frame(height: 34)
                .background(Capsule().fill(on ? AnyShapeStyle(Theme.crimsonGradient) : AnyShapeStyle(Color.clear)))
        }
        .buttonStyle(.plain)
    }

    private var navRow: some View {
        HStack(spacing: 10) {
            pill("arrow.uturn.backward", "Back") { tv.key("KEY_RETURNS") }
            pill("house.fill", "Home") { tv.key("KEY_HOME") }
            pill("line.3.horizontal", "Menu") { tv.key("KEY_MENU") }
            pill("ellipsis", "More") { Haptics.tap(); router.sheet = .more }
        }
    }

    private var mediaRow: some View {
        HStack(spacing: 0) {
            mediaButton("backward.fill", "Rewind") { tv.key("KEY_BACK") }
            mediaButton(playing ? "pause.fill" : "play.fill", playing ? "Pause" : "Play") {
                tv.key(playing ? "KEY_PAUSE" : "KEY_PLAY")
                withAnimation(Theme.spring) { playing.toggle() }
            }
            mediaButton("forward.fill", "Fast forward") { tv.key("KEY_FORWARDS") }
        }
        .frame(height: 52)
        .background(Capsule().fill(Theme.surface2.opacity(0.7)))
    }

    private func round(_ icon: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Theme.text)
                    .key(52)
                Text(label).font(.system(size: 11, weight: .medium)).foregroundStyle(Theme.sub)
            }
        }
        .buttonStyle(PressStyle())
    }

    private func pill(_ icon: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: icon).font(.system(size: 16, weight: .semibold))
                Text(label).font(.system(size: 11, weight: .medium))
            }
            .foregroundStyle(Theme.text)
            .frame(maxWidth: .infinity).frame(height: 58)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.surface2.opacity(0.7)))
        }
        .buttonStyle(PressStyle())
    }

    private func mediaButton(_ icon: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundStyle(Theme.text)
                .contentTransition(.symbolEffect(.replace))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel(label)
    }
}

struct Rocker: View {
    @EnvironmentObject var tv: TVController
    let label: String
    let up: String
    let down: String

    var body: some View {
        VStack(spacing: 0) {
            RepeatButton(action: { tv.key(up) }) {
                Image(systemName: "plus").font(.system(size: 20, weight: .medium))
                    .foregroundStyle(Theme.text)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            Text(label).font(.system(size: 10, weight: .black)).tracking(2).foregroundStyle(Theme.faint)
            RepeatButton(action: { tv.key(down) }) {
                Image(systemName: "minus").font(.system(size: 20, weight: .medium))
                    .foregroundStyle(Theme.text)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(width: 56, height: 172)
        .background(Capsule().fill(Theme.surface2.opacity(0.7)))
    }
}

struct DPad: View {
    @EnvironmentObject var tv: TVController

    var body: some View {
        ZStack {
            Circle().fill(Theme.surface2.opacity(0.7))
            VStack {
                arrow("chevron.up", "KEY_UP")
                Spacer()
                arrow("chevron.down", "KEY_DOWN")
            }
            HStack {
                arrow("chevron.left", "KEY_LEFT")
                Spacer()
                arrow("chevron.right", "KEY_RIGHT")
            }
            Button { tv.key("KEY_OK") } label: {
                Text("OK")
                    .font(.system(size: 17, weight: .heavy))
                    .foregroundStyle(.white)
                    .frame(width: 72, height: 72)
                    .background(Circle().fill(Theme.crimsonGradient))
                    .shadow(color: Theme.crimson.opacity(0.4), radius: 10, y: 5)
            }
            .buttonStyle(PressStyle())
        }
        .frame(width: 186, height: 186)
    }

    private func arrow(_ symbol: String, _ key: String) -> some View {
        RepeatButton(action: { tv.key(key) }) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Theme.sub)
                .frame(width: 58, height: 58)
        }
    }
}

/// Swipe to move, tap to select. Sits where the D-pad was.
struct TouchpadSurface: View {
    @EnvironmentObject var tv: TVController
    @State private var touch: CGPoint?
    @State private var last: CGPoint = .zero
    @State private var acc: CGSize = .zero
    @State private var start: Date = .now
    @State private var moved = false
    @State private var ripples: [Ripple] = []
    @State private var pointer = false

    struct Ripple: Identifiable { let id = UUID(); let at: CGPoint; var grown = false }
    private let step: CGFloat = 34

    var body: some View {
        ZStack(alignment: .topTrailing) {
            ZStack {
                RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Theme.surface2.opacity(0.7))
                Canvas { ctx, size in
                    var y: CGFloat = 11
                    while y < size.height {
                        var x: CGFloat = 11
                        while x < size.width {
                            ctx.fill(Path(ellipseIn: CGRect(x: x - 1.2, y: y - 1.2, width: 2.4, height: 2.4)), with: .color(Theme.faint.opacity(0.35)))
                            x += 22
                        }
                        y += 22
                    }
                }
                Text("Swipe to move \u{00B7} tap to select")
                    .font(.system(size: 12)).foregroundStyle(Theme.faint)
                    .opacity(touch == nil ? 1 : 0)
                if let t = touch {
                    Circle()
                        .fill(RadialGradient(colors: [Theme.crimson.opacity(0.42), .clear], center: .center, startRadius: 0, endRadius: 70))
                        .frame(width: 140, height: 140)
                        .position(t)
                }
                ForEach(ripples) { r in
                    Circle().stroke(Theme.crimson, lineWidth: 2)
                        .frame(width: 24, height: 24)
                        .scaleEffect(r.grown ? 4.6 : 1)
                        .opacity(r.grown ? 0 : 1)
                        .position(r.at)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .contentShape(Rectangle())
            .gesture(drag)

            if tv.current?.brand == .hisense {
            Button {
                tv.key("KEY_LEFTMOUSEKEYS")
                withAnimation(Theme.spring) { pointer.toggle() }
                tv.show(pointer ? "Pointer on" : "Pointer off")
            } label: {
                Image(systemName: pointer ? "cursorarrow.rays" : "cursorarrow")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(pointer ? Color.white : Theme.sub)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(pointer ? AnyShapeStyle(Theme.crimsonGradient) : AnyShapeStyle(Theme.surface)))
            }
            .padding(10)
            .accessibilityLabel("Pointer mode")
            }
        }
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { g in
                if touch == nil {
                    last = g.startLocation
                    acc = .zero
                    moved = false
                    start = .now
                }
                touch = g.location
                acc.width += g.location.x - last.x
                acc.height += g.location.y - last.y
                last = g.location
                if abs(g.translation.width) > 10 || abs(g.translation.height) > 10 { moved = true }
                while abs(acc.width) >= step || abs(acc.height) >= step {
                    if abs(acc.width) >= abs(acc.height) {
                        tv.key(acc.width > 0 ? "KEY_RIGHT" : "KEY_LEFT")
                        acc.width -= acc.width > 0 ? step : -step
                        acc.height *= 0.5
                    } else {
                        tv.key(acc.height > 0 ? "KEY_DOWN" : "KEY_UP")
                        acc.height -= acc.height > 0 ? step : -step
                        acc.width *= 0.5
                    }
                }
            }
            .onEnded { g in
                if !moved && Date.now.timeIntervalSince(start) < 0.3 {
                    tv.key("KEY_OK")
                    let r = Ripple(at: g.startLocation)
                    ripples.append(r)
                    withAnimation(.easeOut(duration: 0.45)) {
                        if let i = ripples.firstIndex(where: { $0.id == r.id }) { ripples[i].grown = true }
                    }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { ripples.removeAll { $0.id == r.id } }
                }
                withAnimation(.easeOut(duration: 0.35)) { touch = nil }
            }
    }
}

// MARK: - Sheets

struct KeyboardSheet: View {
    @EnvironmentObject var tv: TVController
    @State private var value = ""
    @State private var sent = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Type on the TV").font(.system(size: 20, weight: .heavy)).foregroundStyle(Theme.text)
            Text("Select a search box on the TV first.").font(.system(size: 13)).foregroundStyle(Theme.sub)
            HStack(spacing: 10) {
                TextField("", text: $value, prompt: Text("Start typing…").foregroundColor(Theme.faint))
                    .font(.system(size: 18))
                    .foregroundStyle(Theme.text)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.send)
                    .focused($focused)
                    .padding(.horizontal, 16).frame(height: 52)
                    .card(radius: 16)
                    .onChange(of: value) { now in sync(now) }
                    .onSubmit { tv.text("Lit_ENTER"); Haptics.tap(); value = ""; sent = ""; focused = true }
                RepeatButton(action: { tv.text("Lit_BACKSPACE"); Haptics.tick() }) {
                    Image(systemName: "delete.left").font(.system(size: 17)).foregroundStyle(Theme.text).key(52)
                }
                .accessibilityLabel("Backspace")
            }
        }
        .padding(22)
        .onAppear { DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { focused = true } }
    }

    /// Sends only what changed: backspaces for removed text, letters for new text.
    private func sync(_ now: String) {
        let old = Array(sent), new = Array(now)
        var common = 0
        while common < old.count && common < new.count && old[common] == new[common] { common += 1 }
        for _ in common..<old.count { tv.text("Lit_BACKSPACE") }
        for c in new[common...] { tv.text(c == " " ? "Lit_SPACE" : "Lit_\(c)") }
        sent = now
    }
}

/// Buttons you need now and then: numbers, colour keys, info, subtitles, exit.
struct MoreSheet: View {
    @EnvironmentObject var tv: TVController
    private let rows: [[(String, String)]] = [
        [("1", "KEY_1"), ("2", "KEY_2"), ("3", "KEY_3")],
        [("4", "KEY_4"), ("5", "KEY_5"), ("6", "KEY_6")],
        [("7", "KEY_7"), ("8", "KEY_8"), ("9", "KEY_9")],
        [("Info", "KEY_INFO"), ("0", "KEY_0"), ("Subtitles", "KEY_SUBTITLE")],
    ]
    private let colors: [(Color, String, String)] = [(Theme.red, "KEY_RED", "Red"), (Theme.green, "KEY_GREEN", "Green"),
                                                      (Color(hex: 0xEAB308), "KEY_YELLOW", "Yellow"), (Color(hex: 0x3B82F6), "KEY_BLUE", "Blue")]

    private func has(_ f: Feature) -> Bool {
        tv.features.isEmpty ? (tv.current?.brand.features ?? []).contains(f) : tv.features.contains(f)
    }

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 16) {
                Text("More buttons").font(.system(size: 20, weight: .heavy)).foregroundStyle(Theme.text)
                if has(.numbers) {
                VStack(spacing: 10) {
                    ForEach(0..<rows.count, id: \.self) { r in
                        HStack(spacing: 10) {
                            ForEach(rows[r], id: \.1) { item in
                                Button { tv.key(item.1) } label: {
                                    Text(item.0)
                                        .font(.system(size: item.0.count == 1 ? 22 : 13, weight: item.0.count == 1 ? .medium : .semibold))
                                        .foregroundStyle(Theme.text)
                                        .frame(maxWidth: .infinity).frame(height: 56)
                                        .card(radius: 18)
                                }
                                .buttonStyle(PressStyle())
                            }
                        }
                    }
                }
                }
                if has(.colorKeys) {
                HStack(spacing: 12) {
                    ForEach(colors, id: \.1) { c in
                        Button { tv.key(c.1) } label: {
                            RoundedRectangle(cornerRadius: 12).fill(c.0).frame(height: 30)
                        }
                        .buttonStyle(PressStyle())
                        .accessibilityLabel(c.2)
                    }
                }
                }
                Button { tv.key("KEY_EXIT") } label: {
                    Label("Exit", systemImage: "xmark.circle")
                        .font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.text)
                        .frame(maxWidth: .infinity).frame(height: 50)
                        .card(radius: 18)
                }
                .buttonStyle(PressStyle(scale: 0.97))
            }
            .padding(22)
        }
    }
}

struct InputSheet: View {
    @EnvironmentObject var tv: TVController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Choose input").font(.system(size: 20, weight: .heavy)).foregroundStyle(Theme.text)
                Text("Green means something is plugged in and on.")
                    .font(.system(size: 13)).foregroundStyle(Theme.sub)
                    .padding(.bottom, 10)
                if tv.sources.isEmpty {
                    // Some TVs (Samsung) don't list inputs; their own Source button cycles them.
                    Button { tv.key("KEY_SOURCE") } label: {
                        Text("Press the TV's Source button").font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                            .frame(maxWidth: .infinity).frame(height: 48)
                            .background(Capsule().fill(Theme.crimsonGradient))
                    }
                    .buttonStyle(PressStyle(scale: 0.97))
                    .onAppear { tv.refresh() }
                }
                ForEach(tv.sources) { s in
                    Button { tv.choose(s); dismiss() } label: {
                        HStack(spacing: 12) {
                            Circle().fill(s.hasSignal ? Theme.green : Theme.track).frame(width: 9, height: 9)
                            Text(s.displayName).font(.system(size: 16, weight: .medium)).foregroundStyle(Theme.text)
                            Spacer()
                            if s.displayName != s.sourceName {
                                Text(s.sourceName).font(.system(size: 12)).foregroundStyle(Theme.faint)
                            }
                        }
                        .padding(.vertical, 15).padding(.horizontal, 14)
                        .card(radius: 16)
                    }
                    .buttonStyle(PressStyle(scale: 0.98))
                }
            }
            .padding(22)
        }
    }
}

struct PairingSheet: View {
    @EnvironmentObject var tv: TVController
    @State private var code = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 14) {
            Text("Pair with your TV").font(.system(size: 20, weight: .heavy)).foregroundStyle(Theme.text)
            Text(tv.pairingFailed ? "That code didn't match. Try the new one on the TV." : "Type the 4-digit code showing on the TV.")
                .font(.system(size: 13))
                .foregroundStyle(tv.pairingFailed ? Theme.red : Theme.sub)
            TextField("0000", text: $code)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .multilineTextAlignment(.center)
                .font(.system(size: 30, weight: .heavy).monospacedDigit())
                .tracking(14)
                .foregroundStyle(Theme.text)
                .focused($focused)
                .padding(.vertical, 12)
                .card(radius: 16)
                .onChange(of: code) { v in
                    code = String(v.filter(\.isNumber).prefix(4))
                    if code.count == 4 { tv.pair(code: code) }
                }
            Button { tv.pair(code: code) } label: {
                Text("Pair").font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                    .frame(maxWidth: .infinity).frame(height: 52)
                    .background(Capsule().fill(Theme.crimsonGradient))
            }
            .buttonStyle(PressStyle(scale: 0.97))
            Button("Not now") { tv.showPairing = false; tv.disconnectNow() }
                .foregroundStyle(Theme.sub)
        }
        .padding(22)
        .onAppear { focused = true }
        .onChange(of: tv.pairingFailed) { failed in if failed { code = "" } }
    }
}

// MARK: - Apps screen

struct AppsScreen: View {
    @EnvironmentObject var tv: TVController
    @EnvironmentObject var router: SheetRouter
    private let columns = [GridItem(.adaptive(minimum: 78, maximum: 110), spacing: 14)]

    var body: some View {
        ZStack {
            AppBackground()
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 22) {
                    ScreenHeader(title: "Apps")

                    if tv.apps.isEmpty {
                        VStack(spacing: 10) {
                            Image(systemName: "square.grid.2x2").font(.system(size: 34)).foregroundStyle(Theme.faint)
                            Text(tv.status == .connected ? "Loading your TV's apps…" : "Your TV's apps show here once it's connected.")
                                .font(.system(size: 14)).foregroundStyle(Theme.sub).multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity).padding(.vertical, 40)
                        .card(radius: 24)
                    } else {
                        Text("ON YOUR TV").sectionLabel()
                        LazyVGrid(columns: columns, spacing: 18) {
                            ForEach(Array(tv.apps.enumerated()), id: \.element.id) { i, app in
                                AppTile(app: app, index: i)
                            }
                        }
                    }

                    if !tv.sources.isEmpty {
                        Text("INPUTS").sectionLabel().padding(.top, 6)
                        VStack(spacing: 10) {
                            ForEach(tv.sources) { s in
                                Button { tv.choose(s) } label: {
                                    HStack(spacing: 14) {
                                        Image(systemName: s.sourceName.uppercased().hasPrefix("HDMI") ? "cable.connector" : s.sourceName.uppercased() == "TV" ? "antenna.radiowaves.left.and.right" : "rectangle.connected.to.line.below")
                                            .font(.system(size: 16, weight: .semibold))
                                            .foregroundStyle(Theme.crimson)
                                            .frame(width: 38, height: 38)
                                            .background(Circle().fill(Theme.crimson.opacity(0.14)))
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(s.displayName).font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                                            if s.displayName != s.sourceName {
                                                Text(s.sourceName).font(.system(size: 12)).foregroundStyle(Theme.sub)
                                            }
                                        }
                                        Spacer()
                                        Text(s.hasSignal ? "Signal" : "No signal")
                                            .font(.system(size: 11, weight: .semibold))
                                            .foregroundStyle(s.hasSignal ? Theme.green : Theme.faint)
                                    }
                                    .padding(12)
                                    .card(radius: 18)
                                }
                                .buttonStyle(PressStyle(scale: 0.98))
                            }
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 30)
            }
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
            VStack(spacing: 8) {
                ZStack {
                    let tint = Self.tints[app.displayName.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0x7FFFFFFF } % Self.tints.count]
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .fill(LinearGradient(colors: [tint, tint.opacity(0.6)], startPoint: .topLeading, endPoint: .bottomTrailing))
                    Text(String(app.displayName.prefix(1)).uppercased())
                        .font(.system(size: 26, weight: .heavy))
                        .foregroundStyle(.white)
                    if let url = app.logoURL {
                        AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.3))) { phase in
                            if case .success(let img) = phase {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 20, style: .continuous).fill(.white)
                                    img.resizable().interpolation(.high).scaledToFit().padding(12)
                                }
                                .transition(.opacity)
                            }
                        }
                    }
                }
                .frame(width: 70, height: 70)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .shadow(color: .black.opacity(0.12), radius: 8, y: 4)
                Text(app.displayName)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(PressStyle())
        .opacity(shown ? 1 : 0)
        .scaleEffect(shown ? 1 : 0.9)
        .onAppear {
            withAnimation(.spring(response: 0.4, dampingFraction: 0.8).delay(Double(index) * 0.03)) { shown = true }
        }
    }
}


// MARK: - TV features

/// Extra things this TV can do, shown only when it supports them.
struct TVFeaturesCard: View {
    @EnvironmentObject var tv: TVController
    @EnvironmentObject var router: SheetRouter
    let audioOnly: Bool
    let speaker: Bool
    let settings: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("TV FEATURES").sectionLabel()
            HStack(spacing: 10) {
                if audioOnly { tile("tv.slash", "Audio only", "Picture off") { tv.audioOnly() } }
                if speaker { tile("hifispeaker.2.fill", "Speakers", "External volume") { Haptics.tap(); router.sheet = .speaker } }
                if settings { tile("slider.horizontal.3", "TV settings", "Sound, picture") { Haptics.tap(); tv.openTVSettings() } }
            }
        }
        .padding(16)
        .card(radius: 24)
    }

    private func tile(_ icon: String, _ title: String, _ sub: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Theme.crimson)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(Theme.crimson.opacity(0.14)))
                Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.text)
                Text(sub).font(.system(size: 11)).foregroundStyle(Theme.sub).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.surface2.opacity(0.7)))
        }
        .buttonStyle(PressStyle(scale: 0.96))
    }
}

/// Opens the TV's external-speaker (headphone) volume and steps it up or down.
struct SpeakerSheet: View {
    @EnvironmentObject var tv: TVController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 18) {
            VStack(spacing: 6) {
                Text("Speaker volume").font(.system(size: 20, weight: .heavy)).foregroundStyle(Theme.text)
                Text("Adjusts the external speakers or headphones. Watch the slider on the TV.")
                    .font(.system(size: 13)).foregroundStyle(Theme.sub).multilineTextAlignment(.center)
            }
            HStack(spacing: 28) {
                RepeatButton(action: { tv.speakerStep(up: false) }) {
                    Image(systemName: "minus").font(.system(size: 26, weight: .semibold)).foregroundStyle(Theme.text).key(76)
                }
                .accessibilityLabel("Speaker volume down")
                Image(systemName: "hifispeaker.2.fill").font(.system(size: 30)).foregroundStyle(Theme.crimson)
                RepeatButton(action: { tv.speakerStep(up: true) }) {
                    Image(systemName: "plus").font(.system(size: 26, weight: .semibold)).foregroundStyle(Theme.text).key(76)
                }
                .accessibilityLabel("Speaker volume up")
            }
            Button { tv.closeTVPanel(); dismiss() } label: {
                Text("Done").font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                    .frame(maxWidth: .infinity).frame(height: 50)
                    .background(Capsule().fill(Theme.crimsonGradient))
            }
            .buttonStyle(PressStyle(scale: 0.97))
        }
        .padding(22)
        .onAppear { tv.openSpeakerVolume() }
        .onDisappear { tv.closeTVPanel() }
    }
}
