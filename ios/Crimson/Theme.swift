import SwiftUI

enum Theme {
    static let bgTop = Color(hex: 0x1A0B11)
    static let bg = Color(hex: 0x0B0B10)
    static let surface = Color(hex: 0x17171E)
    static let surface2 = Color(hex: 0x20202A)
    static let stroke = Color(hex: 0x2C2C38)
    static let text = Color(hex: 0xF2F2F5)
    static let sub = Color(hex: 0x9A9AAB)
    static let faint = Color(hex: 0x5E5E6E)
    static let crimson = Color(hex: 0xE11D48)
    static let crimsonDark = Color(hex: 0x9F1239)
    static let green = Color(hex: 0x22C55E)
    static let amber = Color(hex: 0xF59E0B)
    static let red = Color(hex: 0xEF4444)

    static let crimsonGradient = LinearGradient(colors: [crimson, crimsonDark], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let spring = Animation.spring(response: 0.32, dampingFraction: 0.62)
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

/// Springy squish on press, used by every control.
struct PressStyle: ButtonStyle {
    var scale: CGFloat = 0.92
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .brightness(configuration.isPressed ? 0.06 : 0)
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
            .brightness(pressed ? 0.08 : 0)
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
