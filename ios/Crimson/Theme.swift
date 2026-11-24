import SwiftUI
import UIKit

/// Light / dark / follow the system.
enum Appearance: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
    var icon: String {
        switch self {
        case .system: return "circle.lefthalf.filled"
        case .light: return "sun.max.fill"
        case .dark: return "moon.fill"
        }
    }
    var scheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

/// How surfaces and the background are drawn.
enum ThemeStyle: String, CaseIterable, Identifiable {
    case glow, solid, oled
    var id: String { rawValue }
    var label: String {
        switch self {
        case .glow: return "Glow"
        case .solid: return "Solid"
        case .oled: return "OLED"
        }
    }
    var blurb: String {
        switch self {
        case .glow: return "Soft moving light behind frosted glass"
        case .solid: return "Calm, flat colours"
        case .oled: return "Pure black, easy on the battery"
        }
    }
}

/// Accent colour families. Crimson is the brand default.
enum Accent: String, CaseIterable, Identifiable {
    case crimson, ocean, emerald, violet, amber, mono
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
    var main: UInt32 {
        switch self {
        case .crimson: return 0xE11D48
        case .ocean: return 0x0EA5E9
        case .emerald: return 0x10B981
        case .violet: return 0x8B5CF6
        case .amber: return 0xF59E0B
        case .mono: return 0x6B7280
        }
    }
    var deep: UInt32 {
        switch self {
        case .crimson: return 0x9F1239
        case .ocean: return 0x0369A1
        case .emerald: return 0x047857
        case .violet: return 0x5B21B6
        case .amber: return 0xB45309
        case .mono: return 0x374151
        }
    }
    /// A second hue used for the moving glow.
    var partner: UInt32 {
        switch self {
        case .crimson: return 0xF97316
        case .ocean: return 0x6366F1
        case .emerald: return 0x06B6D4
        case .violet: return 0xEC4899
        case .amber: return 0xEF4444
        case .mono: return 0x94A3B8
        }
    }
}

enum Theme {
    static var accent: Accent { Accent(rawValue: UserDefaults.standard.string(forKey: "accent") ?? "") ?? .crimson }
    static var style: ThemeStyle { ThemeStyle(rawValue: UserDefaults.standard.string(forKey: "style") ?? "") ?? .glow }

    /// A colour that switches between a light and a dark value with the appearance.
    static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }

    static var bg: Color { style == .oled ? dynamic(0xFFFFFF, 0x000000) : dynamic(0xF4F4F7, 0x0B0B10) }
    static var surface: Color { style == .oled ? dynamic(0xF6F6F8, 0x0E0E12) : dynamic(0xFFFFFF, 0x17171E) }
    static var surface2: Color { style == .oled ? dynamic(0xEEEEF2, 0x16161C) : dynamic(0xF0F0F5, 0x22222C) }
    static var raised: Color { dynamic(0xFFFFFF, 0x24242E) }
    static var stroke: Color { style == .oled ? dynamic(0xE6E6EC, 0x1E1E26) : dynamic(0xE3E3EA, 0x2C2C38) }
    static var text: Color { dynamic(0x14141A, 0xF2F2F5) }
    static var sub: Color { dynamic(0x5C5C6C, 0x9A9AAB) }
    static var faint: Color { dynamic(0x9A9AAB, 0x5E5E6E) }
    static var sheet: Color { dynamic(0xF7F7FA, 0x111116) }
    static var track: Color { dynamic(0xD9D9E2, 0x3A3A48) }

    static var crimson: Color { Color(hex: accent.main) }
    static var crimsonDark: Color { Color(hex: accent.deep) }
    static var partner: Color { Color(hex: accent.partner) }
    static let green = Color(hex: 0x22C55E)
    static let amber = Color(hex: 0xF59E0B)
    static let red = Color(hex: 0xEF4444)

    static var crimsonGradient: LinearGradient {
        LinearGradient(colors: [crimson, crimsonDark], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
    static let spring = Animation.spring(response: 0.32, dampingFraction: 0.62)
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}

/// The screen background for the chosen style. Glow has slowly drifting colour light.
struct AppBackground: View {
    @State private var drift = false

    var body: some View {
        ZStack {
            Theme.bg
            if Theme.style == .glow {
                Circle().fill(Theme.crimson.opacity(0.35))
                    .frame(width: 380, height: 380)
                    .blur(radius: 90)
                    .offset(x: drift ? -120 : -60, y: drift ? -300 : -240)
                Circle().fill(Theme.partner.opacity(0.22))
                    .frame(width: 320, height: 320)
                    .blur(radius: 100)
                    .offset(x: drift ? 150 : 90, y: drift ? -80 : -150)
                Circle().fill(Theme.crimsonDark.opacity(0.25))
                    .frame(width: 360, height: 360)
                    .blur(radius: 110)
                    .offset(x: drift ? 40 : -40, y: drift ? 380 : 320)
            } else if Theme.style == .solid {
                LinearGradient(colors: [Theme.crimson.opacity(0.10), .clear], startPoint: .top, endPoint: .center)
            }
        }
        .ignoresSafeArea()
        .onAppear {
            guard Theme.style == .glow else { return }
            withAnimation(.easeInOut(duration: 9).repeatForever(autoreverses: true)) { drift = true }
        }
    }
}

/// Springy squish on press, used by every control.
struct PressStyle: ButtonStyle {
    var scale: CGFloat = 0.92
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(configuration.isPressed ? .easeOut(duration: 0.08) : Theme.spring, value: configuration.isPressed)
    }
}

/// A control that fires once on touch-down and keeps firing while held (volume, arrows).
struct RepeatButton<Label: View>: View {
    let action: () -> Void
    @ViewBuilder let label: () -> Label
    @State private var pressed = false
    @State private var timer: Timer?

    var body: some View {
        label()
            .scaleEffect(pressed ? 0.9 : 1)
            .opacity(pressed ? 0.8 : 1)
            .animation(pressed ? .easeOut(duration: 0.08) : Theme.spring, value: pressed)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !pressed else { return }
                        pressed = true
                        action()
                        timer = Timer.scheduledTimer(withTimeInterval: 0.42, repeats: false) { _ in
                            timer = Timer.scheduledTimer(withTimeInterval: 0.13, repeats: true) { _ in action() }
                        }
                    }
                    .onEnded { _ in
                        pressed = false
                        timer?.invalidate()
                        timer = nil
                    }
            )
    }
}

extension View {
    /// A card surface: frosted glass in the Glow style, solid otherwise.
    @ViewBuilder
    func card(radius: CGFloat = 24, fill: Color? = nil) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        if Theme.style == .glow && fill == nil {
            self.background(shape.fill(.ultraThinMaterial))
                .overlay(shape.stroke(Color.white.opacity(0.10), lineWidth: 1))
        } else {
            self.background(shape.fill(fill ?? Theme.surface))
                .overlay(shape.stroke(Theme.stroke, lineWidth: 1))
        }
    }

    /// A round control surface used by remote buttons.
    func key(_ size: CGFloat? = nil) -> some View {
        self.frame(width: size, height: size)
            .background(Circle().fill(Theme.style == .glow ? AnyShapeStyle(.thinMaterial) : AnyShapeStyle(Theme.surface2)))
            .overlay(Circle().stroke(Theme.style == .glow ? Color.white.opacity(0.10) : Theme.stroke, lineWidth: 1))
    }

    /// A small uppercase section label.
    func sectionLabel() -> some View {
        self.font(.system(size: 11, weight: .bold)).tracking(1.8).foregroundStyle(Theme.faint)
    }
}
