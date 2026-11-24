import Foundation

/// What a TV can do. The remote shows only the controls the connected TV supports.
enum Feature: String, CaseIterable {
    case power, volumeSlider, mute, channels, numbers, colorKeys
    case inputList, sourceKey, apps, keyboard, pointer, media
    case cast, audioOnly, speakerVolume, tvSettings
}

extension TVBrand {
    /// What each brand's remote protocol supports. Refined per TV once connected.
    var features: Set<Feature> {
        switch self {
        case .hisense:
            // Audio only and speaker volume work through the VIDAA quick menu (tested on a 50A6).
            return [.power, .volumeSlider, .mute, .channels, .numbers, .colorKeys, .inputList, .apps,
                    .keyboard, .pointer, .media, .cast, .audioOnly, .speakerVolume, .tvSettings]
        case .roku:
            return [.power, .mute, .apps, .keyboard, .media]
        case .samsung:
            return [.power, .mute, .channels, .numbers, .colorKeys, .sourceKey, .apps, .keyboard, .media, .cast]
        case .lg:
            return [.power, .volumeSlider, .mute, .channels, .numbers, .colorKeys, .inputList, .apps,
                    .keyboard, .pointer, .media, .cast, .audioOnly]
        }
    }
}

extension TVController {
    func has(_ f: Feature) -> Bool { features.contains(f) }

    /// Plays a sequence of keys with a short gap, for features reached through the TV's own menus.
    func run(_ keys: [String], gap: Double = 0.7) {
        Task { @MainActor in
            for k in keys {
                key(k)
                try? await Task.sleep(nanoseconds: UInt64(gap * 1_000_000_000))
            }
        }
    }

    /// Picture off, sound on.
    func audioOnly() {
        if let lg = driverAsLG { lg.screenOff(); show("Picture off \u{00B7} press any button to bring it back"); return }
        guard current?.brand == .hisense else { return }
        run(["KEY_MENU", "KEY_DOWN", "KEY_OK"], gap: 0.9)
        show("Picture off \u{00B7} press any button to bring it back")
    }

    /// Opens the external-speaker (headphone) volume on the TV; then use speakerStep and closeTVPanel.
    func openSpeakerVolume() {
        guard current?.brand == .hisense else { return }
        run(["KEY_MENU", "KEY_OK"], gap: 0.9)
    }

    func speakerStep(up: Bool) { key(up ? "KEY_RIGHT" : "KEY_LEFT") }

    func closeTVPanel() { key("KEY_RETURNS") }

    /// Opens the TV's own settings (for speaker output and anything else Crimson can't set directly).
    func openTVSettings() {
        guard current?.brand == .hisense else { key("KEY_MENU"); return }
        run(["KEY_MENU", "KEY_DOWN", "KEY_DOWN", "KEY_DOWN", "KEY_OK"], gap: 0.8)
    }
}
