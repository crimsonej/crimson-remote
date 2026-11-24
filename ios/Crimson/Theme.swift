import SwiftUI
import UIKit

/// Light / dark / follow the system.
enum Appearance: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
    var scheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
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
}

enum Theme {
    static var accent: Accent {
        Accent(rawValue: UserDefaults.standard.string(forKey: "accent") ?? "") ?? .crimson
    }

    /// A colour that switches between a light and a dark value with the system appearance.
    static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }

    static var bg: Color { dynamic(0xF5F5F8, 0x0B0B10) }
    static var surface: Color { dynamic(0xFFFFFF, 0x17171E) }
    static var surface2: Color { dynamic(0xF0F0F5, 0x20202A) }
    static var raised: Color { dynamic(0xFFFFFF, 0x22222C) }
    static var stroke: Color { dynamic(0xE3E3EA, 0x2C2C38) }
    static var text: Color { dynamic(0x14141A, 0xF2F2F5) }
    static var sub: Color { dynamic(0x5C5C6C, 0x9A9AAB) }
    static var faint: Color { dynamic(0x9A9AAB, 0x5E5E6E) }
    static var sheet: Color { dynamic(0xF7F7FA, 0x15151C) }
    static var toast: Color { dynamic(0x1F1F27, 0x2A2A35) }
    static var track: Color { dynamic(0xD9D9E2, 0x3A3A48) }

    /// Kept as `crimson` so the brand reads clearly in code; it follows the chosen accent.
    static var crimson: Color { Color(hex: accent.main) }
    static var crimsonDark: Color { Color(hex: accent.deep) }
    static let green = Color(hex: 0x22C55E)
    static let amber = Color(hex: 0xF59E0B)
    static let red = Color(hex: 0xEF4444)

    static var crimsonGradient: LinearGradient {
        LinearGradient(colors: [crimson, crimsonDark], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
    static var backdrop: LinearGradient {
        LinearGradient(colors: [crimson.opacity(0.16), bg, bg], startPoint: .top, endPoint: .bottom)
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
    func card(radius: CGFloat = 24, fill: Color = Theme.surface) -> some View {
        background(RoundedRectangle(cornerRadius: radius, style: .continuous).fill(fill))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(Theme.stroke, lineWidth: 1))
    }
}
