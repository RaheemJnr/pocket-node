import SwiftUI

/// Color tokens mirroring the Android Material palette in
/// `android/app/src/main/java/com/rjnr/pocketnode/ui/theme/Theme.kt`.
///
/// Kept as a plain struct so it can be handed around by the `AppContainer`
/// without pulling in a styling framework.
struct Theme {
    let primary: Color
    let secondary: Color
    let background: Color
    let surface: Color
    let error: Color

    /// Light scheme: `LightColorScheme` in Theme.kt (Material 3 defaults for the
    /// tokens the Android side does not override).
    static let light = Theme(
        primary: Color(hex: 0x1976D2),
        secondary: Color(hex: 0x0288D1),
        background: Color(hex: 0xFFFBFE),
        surface: Color(hex: 0xFFFBFE),
        error: Color(hex: 0xB3261E)
    )

    /// Dark scheme: `DarkColorScheme` in Theme.kt. `primary` is the PocketGreen
    /// brand color.
    static let dark = Theme(
        primary: Color(hex: 0x1DD781),
        secondary: Color(hex: 0x81D4FA),
        background: Color(hex: 0x0D0D0D),
        surface: Color(hex: 0x1A1A1A),
        error: Color(hex: 0xF2B8B5)
    )

    static func forScheme(_ scheme: ColorScheme) -> Theme {
        scheme == .dark ? .dark : .light
    }

    /// Transaction-status colors, the same three the Android theme declares
    /// outside its two schemes (`SuccessGreen`, `ErrorRed`, `PendingAmber` in
    /// `Theme.kt`). Scheme-independent there and here: a failed transaction has
    /// to read as failed in both, and a status color that flipped with the
    /// scheme would stop meaning one thing.
    static let successGreen = Color(hex: 0x22C55E)
    static let errorRed = Color(hex: 0xFF4444)
    static let pendingAmber = Color(hex: 0xF59E0B)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: 1.0
        )
    }
}
