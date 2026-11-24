import SwiftUI

// MARK: - Remote

struct RemotePad: View {
    @EnvironmentObject var tv: TVController
    var openInputs: () -> Void
    @State private var playing = true
    @State private var more = false

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { tv.power() } label: {
                    Image(systemName: "power")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 62, height: 62)
                        .background(Circle().fill(Theme.crimsonGradient))
                        .shadow(color: Theme.crimson.opacity(0.45), radius: 14, y: 8)
                }
                .buttonStyle(PressStyle())
                Spacer()
                Button { Haptics.tap(); openInputs() } label: {
                    Text("Input")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Theme.text)
                        .frame(width: 116, height: 52)
                        .card(radius: 26, fill: Theme.surface2)
                }
                .buttonStyle(PressStyle())
                Spacer()
                Button { tv.key("KEY_MUTE") } label: {
                    Image(systemName: "speaker.slash.fill")
                        .font(.system(size: 20))
                        .foregroundStyle(Theme.text)
                        .frame(width: 62, height: 62)
                        .background(Circle().fill(Theme.surface2))
                        .overlay(Circle().stroke(Theme.stroke))
                }
                .buttonStyle(PressStyle())
            }

            HStack {
                Rocker(label: "VOL", up: "KEY_VOLUMEUP", down: "KEY_VOLUMEDOWN")
                Spacer()
                DPad()
                Spacer()
                Rocker(label: "CH", up: "KEY_CHANNELUP", down: "KEY_CHANNELDOWN")
            }
            .padding(.top, 22)

            HStack(spacing: 8) {
                chip("Back", "KEY_RETURNS")
                chip("Home", "KEY_HOME")
                chip("Menu", "KEY_MENU")
                chip("Exit", "KEY_EXIT")
            }
            .padding(.top, 22)

            HStack(spacing: 0) {
                mediaButton("backward.fill", "KEY_BACK")
                Button {
                    tv.key(playing ? "KEY_PAUSE" : "KEY_PLAY")
                    withAnimation(Theme.spring) { playing.toggle() }
                } label: {
                    Image(systemName: playing ? "pause.fill" : "play.fill")
                        .font(.system(size: 20))
                        .foregroundStyle(Theme.text)
                        .contentTransition(.symbolEffect(.replace))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                mediaButton("forward.fill", "KEY_FORWARDS")
            }
            .padding(6)
            .frame(height: 58)
            .card(radius: 29)
            .padding(.top, 14)

            Button {
                Haptics.tick()
                withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { more.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Text(more ? "Fewer buttons" : "More buttons")
                    Image(systemName: "chevron.down").rotationEffect(.degrees(more ? 180 : 0))
                }
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Theme.sub)
                .padding(.vertical, 10).padding(.horizontal, 16)
            }
            .buttonStyle(.plain)
            .padding(.top, 6)

            if more {
                MoreButtons().transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private func chip(_ title: String, _ key: String) -> some View {
        Button { tv.key(key) } label: {
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.text)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .card(radius: 24, fill: Theme.surface2)
        }
        .buttonStyle(PressStyle())
    }

    private func mediaButton(_ symbol: String, _ key: String) -> some View {
        Button { tv.key(key) } label: {
            Image(systemName: symbol)
                .font(.system(size: 18))
                .foregroundStyle(Theme.text)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
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
            Text(label)
                .font(.system(size: 10, weight: .black))
                .tracking(2)
                .foregroundStyle(Theme.faint)
            RepeatButton(action: { tv.key(down) }) {
                Image(systemName: "minus").font(.system(size: 20, weight: .medium))
                    .foregroundStyle(Theme.text)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(width: 58, height: 178)
        .card(radius: 29)
    }
}

struct DPad: View {
    @EnvironmentObject var tv: TVController

    var body: some View {
        ZStack {
            Circle()
                .fill(LinearGradient(colors: [Theme.raised, Theme.surface], startPoint: .top, endPoint: .bottom))
                .overlay(Circle().stroke(Theme.stroke))
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
                    .frame(width: 76, height: 76)
                    .background(Circle().fill(Theme.crimsonGradient))
                    .shadow(color: Theme.crimson.opacity(0.45), radius: 12, y: 6)
            }
            .buttonStyle(PressStyle())
        }
        .frame(width: 194, height: 194)
    }

    private func arrow(_ symbol: String, _ key: String) -> some View {
        RepeatButton(action: { tv.key(key) }) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Theme.sub)
                .frame(width: 60, height: 60)
        }
    }
}

struct MoreButtons: View {
    @EnvironmentObject var tv: TVController
    private let rows: [[(String, String)]] = [
        [("1", "KEY_1"), ("2", "KEY_2"), ("3", "KEY_3")],
        [("4", "KEY_4"), ("5", "KEY_5"), ("6", "KEY_6")],
        [("7", "KEY_7"), ("8", "KEY_8"), ("9", "KEY_9")],
        [("Info", "KEY_INFO"), ("0", "KEY_0"), ("Subs", "KEY_SUBTITLE")],
    ]
    private let colors: [(Color, String)] = [(Theme.red, "KEY_RED"), (Theme.green, "KEY_GREEN"),
                                              (Color(hex: 0xEAB308), "KEY_YELLOW"), (Color(hex: 0x3B82F6), "KEY_BLUE")]

    var body: some View {
        VStack(spacing: 8) {
            ForEach(0..<rows.count, id: \.self) { r in
                HStack(spacing: 8) {
                    ForEach(rows[r], id: \.1) { item in
                        Button { tv.key(item.1) } label: {
                            Text(item.0)
                                .font(.system(size: item.0.count == 1 ? 20 : 13, weight: item.0.count == 1 ? .medium : .semibold))
                                .foregroundStyle(Theme.text)
                                .frame(maxWidth: .infinity).frame(height: 50)
                                .card(radius: 18, fill: Theme.surface2)
                        }
                        .buttonStyle(PressStyle())
                    }
                }
            }
            HStack(spacing: 16) {
                ForEach(colors, id: \.1) { c in
                    Button { tv.key(c.1) } label: {
                        RoundedRectangle(cornerRadius: 10).fill(c.0).frame(height: 22)
                    }
                    .buttonStyle(PressStyle())
                }
            }
            .padding(.horizontal, 8).padding(.top, 8)
        }
    }
}

// MARK: - Touchpad

struct TouchpadPanel: View {
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
        VStack(spacing: 0) {
            GeometryReader { geo in
                ZStack {
                    RoundedRectangle(cornerRadius: 28, style: .continuous).fill(Theme.surface)
                    Canvas { ctx, size in
                        var y: CGFloat = 11
                        while y < size.height {
                            var x: CGFloat = 11
                            while x < size.width {
                                ctx.fill(Path(ellipseIn: CGRect(x: x - 1.3, y: y - 1.3, width: 2.6, height: 2.6)), with: .color(.white.opacity(0.08)))
                                x += 22
                            }
                            y += 22
                        }
                    }
                    if let t = touch {
                        Circle()
                            .fill(RadialGradient(colors: [Theme.crimson.opacity(0.42), .clear], center: .center, startRadius: 0, endRadius: 70))
                            .frame(width: 140, height: 140)
                            .position(t)
                            .transition(.opacity)
                    }
                    ForEach(ripples) { r in
                        Circle().stroke(Theme.crimson, lineWidth: 2)
                            .frame(width: 24, height: 24)
                            .scaleEffect(r.grown ? 4.6 : 1)
                            .opacity(r.grown ? 0 : 1)
                            .position(r.at)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).stroke(Theme.stroke))
                .contentShape(Rectangle())
                .gesture(
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
                )
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .frame(height: 330)

            Text("Swipe to move · Tap to select")
                .font(.system(size: 12))
                .foregroundStyle(Theme.faint)
                .padding(.top, 10)

            HStack(spacing: 8) {
                Button {
                    tv.key("KEY_LEFTMOUSEKEYS")
                    withAnimation(Theme.spring) { pointer.toggle() }
                    tv.show(pointer ? "Pointer on: swipe moves the cursor" : "Pointer off")
                } label: {
                    Text("Pointer mode")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(pointer ? .white : Theme.text)
                        .frame(maxWidth: .infinity).frame(height: 46)
                        .background(Capsule().fill(pointer ? AnyShapeStyle(Theme.crimsonGradient) : AnyShapeStyle(Theme.surface2)))
                        .overlay(Capsule().stroke(pointer ? Color.clear : Theme.stroke))
                }
                .buttonStyle(PressStyle())
                smallChip("Back", "KEY_RETURNS")
                smallChip("Home", "KEY_HOME")
            }
            .padding(.top, 14)
        }
    }

    private func smallChip(_ t: String, _ k: String) -> some View {
        Button { tv.key(k) } label: {
            Text(t)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.text)
                .frame(maxWidth: .infinity).frame(height: 46)
                .card(radius: 23, fill: Theme.surface2)
        }
        .buttonStyle(PressStyle())
    }
}

// MARK: - Keyboard

struct KeyboardPanel: View {
    @EnvironmentObject var tv: TVController
    @State private var value = ""
    @State private var sent = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Select a search box on the TV, then type here.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.sub)
            TextField("", text: $value, prompt: Text("Type to send to the TV…").foregroundColor(Theme.faint))
                .font(.system(size: 18))
                .foregroundStyle(Theme.text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.send)
                .focused($focused)
                .padding(.horizontal, 18).padding(.vertical, 16)
                .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Theme.surface))
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(focused ? Theme.crimson : Theme.stroke, lineWidth: 1))
                .animation(.easeOut(duration: 0.2), value: focused)
                .onChange(of: value) { now in sync(now) }
                .onSubmit { tv.text("Lit_ENTER"); Haptics.tap(); focused = true }

            HStack(spacing: 8) {
                Button { value = "" } label: { chipLabel("Clear") }.buttonStyle(PressStyle())
                RepeatButton(action: { tv.text("Lit_BACKSPACE"); Haptics.tick() }) {
                    Image(systemName: "delete.left")
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.text)
                        .frame(maxWidth: .infinity).frame(height: 46)
                        .card(radius: 23, fill: Theme.surface2)
                }
                Button { tv.text("Lit_ENTER"); Haptics.tap() } label: {
                    Text("Enter")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity).frame(height: 46)
                        .background(Capsule().fill(Theme.crimsonGradient))
                }
                .buttonStyle(PressStyle())
            }
        }
        .onAppear { DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { focused = true } }
    }

    private func chipLabel(_ t: String) -> some View {
        Text(t)
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(Theme.text)
            .frame(maxWidth: .infinity).frame(height: 46)
            .card(radius: 23, fill: Theme.surface2)
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

// MARK: - Sheets

struct InputSheet: View {
    @EnvironmentObject var tv: TVController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Choose input").font(.system(size: 20, weight: .heavy))
            Text("Green means something is plugged in and on.")
                .font(.system(size: 13)).foregroundStyle(Theme.sub)
                .padding(.bottom, 10)
            if tv.sources.isEmpty {
                Text("Loading inputs…").foregroundStyle(Theme.sub).onAppear { tv.refresh() }
            }
            ForEach(tv.sources) { s in
                Button { tv.choose(s); dismiss() } label: {
                    HStack(spacing: 12) {
                        Circle().fill(s.hasSignal ? Theme.green : Theme.track).frame(width: 8, height: 8)
                        Text(s.displayName).font(.system(size: 16, weight: .medium)).foregroundStyle(Theme.text)
                        Spacer()
                        if s.displayName != s.sourceName {
                            Text(s.sourceName).font(.system(size: 12)).foregroundStyle(Theme.faint)
                        }
                    }
                    .padding(.vertical, 14).padding(.horizontal, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle(scale: 0.97))
            }
            Spacer()
        }
        .padding(22)
    }
}

struct PairingSheet: View {
    @EnvironmentObject var tv: TVController
    @State private var code = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 14) {
            Text("Pair with your TV").font(.system(size: 20, weight: .heavy))
            Text(tv.pairingFailed ? "That code didn't match. Try the new one on the TV." : "Type the 4-digit code showing on the TV.")
                .font(.system(size: 13))
                .foregroundStyle(tv.pairingFailed ? Theme.red : Theme.sub)
            TextField("0000", text: $code)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .multilineTextAlignment(.center)
                .font(.system(size: 30, weight: .heavy).monospacedDigit())
                .tracking(14)
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
            Button("Not now") { tv.showPairing = false; tv.status = .offline }
                .foregroundStyle(Theme.sub)
        }
        .padding(22)
        .onAppear { focused = true }
        .onChange(of: tv.pairingFailed) { failed in if failed { code = "" } }
    }
}
