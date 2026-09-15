import SwiftUI
import UIKit

/// The 6-digit pad, with nothing behind it.
///
/// Purely presentational: it owns no PIN state and calls nothing. The parent
/// holds the digits, decides what a completed entry means, and supplies the
/// copy. That keeps the one piece of UI a PIN passes through free of any
/// storage or crypto, and lets it be rendered in a test without a Keychain.
///
/// No keyboard on purpose. A custom pad cannot be recorded by a third-party
/// keyboard, has no predictive bar to leak into, and is what every wallet and
/// banking app on the platform uses. It also matches Android's
/// `PinEntryScreen`, down to the shake and the haptics.
struct PinEntryView: View {
    let title: String
    var subtitle: String?
    /// Neutral status line under the dots, such as the attempts remaining.
    var footnote: String?
    /// Error line under the dots. Replaces ``footnote`` while it is set.
    var error: String?
    /// Bumped by the parent for each new error, so two identical messages in a
    /// row still shake. Comparing the text would swallow the second one.
    var errorToken: Int = 0
    /// Shows a progress line and blocks input while Argon2id runs.
    var isBusy: Bool = false
    /// False while locked out, so the pad is visible but inert.
    var isEnabled: Bool = true

    @Binding var digits: String

    /// Fired once the last digit lands.
    var onComplete: (String) -> Void

    private let length = PinService.pinLength
    @State private var shakeOffset: CGFloat = 0

    private var acceptsInput: Bool { isEnabled && !isBusy }

    var body: some View {
        VStack(spacing: 24) {
            VStack(spacing: 8) {
                Text(title)
                    .font(.title2.weight(.semibold))
                    .multilineTextAlignment(.center)

                if let subtitle {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }

            dots
                .offset(x: shakeOffset)
                .accessibilityElement()
                .accessibilityLabel("\(digits.count) of \(length) digits entered")
                .accessibilityIdentifier("pin.dots")

            status

            keypad
                .disabled(!acceptsInput)
                .opacity(acceptsInput ? 1 : 0.4)
        }
        .onChange(of: errorToken) { _, _ in shake() }
    }

    private var dots: some View {
        HStack(spacing: 18) {
            ForEach(0..<length, id: \.self) { index in
                Circle()
                    .strokeBorder(Color.primary.opacity(0.35), lineWidth: 1.5)
                    .background(
                        Circle().fill(index < digits.count ? Color.primary : .clear)
                    )
                    .frame(width: 16, height: 16)
            }
        }
    }

    @ViewBuilder
    private var status: some View {
        if isBusy {
            HStack(spacing: 8) {
                ProgressView()
                Text("Verifying...")
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        } else if let error {
            Text(error)
                .font(.footnote)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
                .accessibilityIdentifier("pin.error")
        } else if let footnote {
            Text(footnote)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        } else {
            // Holds the line's height so the pad does not jump when a message
            // appears or clears.
            Text(" ").font(.footnote)
        }
    }

    private var keypad: some View {
        Grid(horizontalSpacing: 24, verticalSpacing: 16) {
            ForEach(Self.rows, id: \.first) { row in
                GridRow {
                    ForEach(row, id: \.self) { key in
                        keyButton(key)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func keyButton(_ key: String) -> some View {
        switch key {
        case "":
            Color.clear.frame(width: 72, height: 62)
        case "delete":
            Button(action: backspace) {
                Image(systemName: "delete.left")
                    .font(.title2)
                    .frame(width: 72, height: 62)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Delete")
            .accessibilityIdentifier("pin.delete")
        default:
            Button {
                append(key)
            } label: {
                Text(key)
                    .font(.title.weight(.regular))
                    .frame(width: 72, height: 62)
                    .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 16))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("pin.key.\(key)")
        }
    }

    private func append(_ key: String) {
        guard acceptsInput, digits.count < length else { return }
        Self.tap()
        digits.append(key)
        if digits.count == length {
            onComplete(digits)
        }
    }

    private func backspace() {
        guard acceptsInput, !digits.isEmpty else { return }
        Self.tap()
        digits.removeLast()
    }

    private func shake() {
        Self.error()
        // Three decaying swings, matching the Android `shakeOffset` animation.
        withAnimation(.easeInOut(duration: 0.06).repeatCount(5, autoreverses: true)) {
            shakeOffset = 10
        }
        withAnimation(.easeInOut(duration: 0.06).delay(0.3)) {
            shakeOffset = 0
        }
    }

    // Both respect the system "haptics" setting; neither fires in the
    // simulator, which has no Taptic Engine.
    private static func tap() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    private static func error() {
        UINotificationFeedbackGenerator().notificationOccurred(.error)
    }

    private static let rows: [[String]] = [
        ["1", "2", "3"],
        ["4", "5", "6"],
        ["7", "8", "9"],
        ["", "0", "delete"],
    ]
}
