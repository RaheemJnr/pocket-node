import PocketNodeCore
import SwiftUI

/// The four sync modes as the user meets them, and the explainer above them.
///
/// Ported from the Android strings (`edu_sync_*`, `sync_mode_*` in
/// `app/src/main/res/values/strings.xml`) so the two apps teach the same thing
/// in the same words. The only edits are typographic: Android's em dashes are
/// commas here, per the repository's copy rule.
enum SyncCopy {
    static let headerTitle = "Why is the app syncing?"
    static let headerBody = "Your phone is checking the blockchain itself, no middleman server. The first sync takes about 2 minutes; after that, updates are quick."

    static let newWalletTitle = "New wallet"
    static let newWalletBody = "Pick this if you have never received CKB before. Instant, no past activity to find."

    static let recentTitle = "Recent activity"
    static let recentBody = "Pick this if you used a CKB wallet in the last month. Takes about 2 minutes; finds activity from the last 30 days."

    static let customTitle = "From a specific date"
    static let customBody = "Pick this if you know roughly when your first CKB transaction was. You will enter a block number, we will help you find it from the explorer."

    static let fullHistoryTitle = "All history"
    static let fullHistoryBody = "Pick this only if you need every transaction since CKB launched. Slow, can take hours on mainnet."

    static let customFieldLabel = "Block height"
    static let customFieldPlaceholder = "e.g. 12000000"
    static let customInvalid = "Enter a block number above zero."

    /// Presentation order, the same one Android lists them in.
    ///
    /// Computed rather than stored: `SyncMode` comes from Kotlin and is not
    /// `Sendable`, so a stored static would be shared mutable state as far as
    /// Swift 6 is concerned. The four values are process-wide singletons, so
    /// rebuilding the array costs nothing.
    static var order: [SyncMode] { [.theNewWallet, .recent, .custom, .fullHistory] }

    // `SyncMode` arrives from Kotlin as a class, not a Swift enum (SKIE's enum
    // interop is off), so these compare with `==` rather than switching.

    // Every branch names its own mode, the last one included, so a fifth
    // SyncMode added on the Kotlin side falls through to the fallback instead
    // of silently wearing All history's dangerous copy.

    static func title(for mode: SyncMode) -> String {
        if mode == .theNewWallet { return newWalletTitle }
        if mode == .recent { return recentTitle }
        if mode == .custom { return customTitle }
        if mode == .fullHistory { return fullHistoryTitle }
        return mode.name
    }

    static func body(for mode: SyncMode) -> String {
        if mode == .theNewWallet { return newWalletBody }
        if mode == .recent { return recentBody }
        if mode == .custom { return customBody }
        if mode == .fullHistory { return fullHistoryBody }
        return ""
    }

    /// The accessibility identifier suffix for a mode's option row.
    static func identifier(for mode: SyncMode) -> String {
        if mode == .theNewWallet { return "newWallet" }
        if mode == .recent { return "recent" }
        if mode == .custom { return "custom" }
        if mode == .fullHistory { return "fullHistory" }
        return mode.name.lowercased()
    }

    /// The one mode Android marks as the recommended default
    /// (`sync_mode_recent_recommended`).
    static func isRecommended(_ mode: SyncMode) -> Bool { mode == .recent }

    static let recommendedBadge = "Recommended"
}

/// Selection and validation for ``SyncModeSheet``, with no SwiftUI in it.
///
/// Split out so the one rule worth testing is testable: a custom start needs a
/// block number above zero, and the other three modes need nothing at all.
@MainActor
@Observable
final class SyncModeSheetModel {
    var selected: SyncMode = .recent
    var customHeight: String = ""
    private(set) var isSubmitting = false

    init(selected: SyncMode? = nil, customHeight: Int64? = nil) {
        if let selected { self.selected = selected }
        if let customHeight { self.customHeight = String(customHeight) }
    }

    /// Whether the block-height field belongs on screen.
    var requiresHeight: Bool { selected == .custom }

    /// The typed height, or nil when the field is empty or not a number.
    var parsedHeight: Int64? {
        Int64(customHeight.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    /// Whether the typed height is a block number the chain could have.
    /// Meaningless, and always true, for the three modes that take no height.
    var hasValidHeight: Bool {
        guard requiresHeight else { return true }
        guard let parsedHeight else { return false }
        return parsedHeight > 0
    }

    /// True once the current selection could be applied.
    var canConfirm: Bool { !isSubmitting && hasValidHeight }

    /// The height to hand the sync layer: only ever set for a custom start.
    var heightToApply: Int64? { requiresHeight ? parsedHeight : nil }

    func beginSubmitting() { isSubmitting = true }
    func endSubmitting() { isSubmitting = false }
}

/// Where the user says how far back to look.
///
/// Android puts the same four options behind the Home sync card and behind
/// Settings; this is the one place on iOS, reached from the card either way.
struct SyncModeSheet: View {
    let theme: Theme
    /// Applies the choice. Answers false when the light client refused it.
    let onApply: (SyncMode, Int64?) async -> Bool
    /// Whatever the last attempt failed with, shown under the button.
    let errorMessage: String?

    @State private var model: SyncModeSheetModel
    @Environment(\.dismiss) private var dismiss

    init(
        theme: Theme,
        selected: SyncMode? = nil,
        customHeight: Int64? = nil,
        errorMessage: String? = nil,
        onApply: @escaping (SyncMode, Int64?) async -> Bool
    ) {
        self.theme = theme
        self.errorMessage = errorMessage
        self.onApply = onApply
        _model = State(wrappedValue: SyncModeSheetModel(selected: selected, customHeight: customHeight))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    explainer

                    // Keyed by the Kotlin constant name: `SyncMode` is a class
                    // here, and `\.self` would need it to be Hashable.
                    ForEach(SyncCopy.order, id: \.name) { mode in
                        option(mode)
                    }

                    if model.requiresHeight {
                        customHeightField
                    }

                    if let errorMessage {
                        Text(errorMessage)
                            .font(.footnote)
                            .foregroundStyle(theme.error)
                    }

                    confirmButton
                }
                .padding()
            }
            .background(theme.background)
            .navigationTitle("Sync")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("syncMode.cancel")
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("syncMode.root")
    }

    private var explainer: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(SyncCopy.headerTitle)
                .font(.headline)
            Text(SyncCopy.headerBody)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
    }

    private func option(_ mode: SyncMode) -> some View {
        let isSelected = model.selected == mode
        return Button {
            model.selected = mode
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: isSelected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(isSelected ? theme.primary : .secondary)
                    .font(.title3)

                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(SyncCopy.title(for: mode))
                            .font(.headline)
                        if SyncCopy.isRecommended(mode) {
                            Text(SyncCopy.recommendedBadge)
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 2)
                                .background(theme.primary.opacity(0.15), in: Capsule())
                                .accessibilityIdentifier("syncMode.recommended")
                        }
                    }
                    Text(SyncCopy.body(for: mode))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                }

                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .buttonStyle(.plain)
        .background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(isSelected ? theme.primary : .clear, lineWidth: 1.5)
        )
        .accessibilityIdentifier("syncMode.option.\(SyncCopy.identifier(for: mode))")
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }

    private var customHeightField: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(SyncCopy.customFieldLabel)
                .font(.subheadline)
            TextField(SyncCopy.customFieldPlaceholder, text: $model.customHeight)
                .keyboardType(.numberPad)
                .textFieldStyle(.roundedBorder)
                .accessibilityIdentifier("syncMode.customHeight")
            // Gated on the height itself, not on `canConfirm`: that also goes
            // false while a registration is in flight, and flashing "enter a
            // block number above zero" at a valid one is worse than silence.
            if !model.customHeight.isEmpty && !model.hasValidHeight {
                Text(SyncCopy.customInvalid)
                    .font(.footnote)
                    .foregroundStyle(theme.error)
            }
        }
    }

    private var confirmButton: some View {
        Button {
            Task {
                model.beginSubmitting()
                let applied = await onApply(model.selected, model.heightToApply)
                model.endSubmitting()
                if applied { dismiss() }
            }
        } label: {
            Text(model.isSubmitting ? "Starting..." : "Start syncing")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .disabled(!model.canConfirm)
        .accessibilityIdentifier("syncMode.confirm")
    }
}
