import Foundation
import PocketNodeCore

/// `UserDefaults`-backed implementation of every `core.prefs` domain from the
/// shared KMP module (`android/shared/.../core/prefs/{Sync,Ui,Network,AppState}Preferences.kt`).
///
/// Mirrors the Android implementation,
/// `android/app/.../data/wallet/WalletPreferences.kt`, key for key: the same
/// key strings (documented next to each one below), the same defaults, and
/// the same per-network / per-wallet suffix scheme. A `network: NetworkType?`
/// parameter follows the Kotlin convention: `nil` means "the currently
/// selected network". A `walletId: String?` parameter follows it too: `nil`
/// means "the network-wide value", non-`nil` means "this wallet's value".
///
/// Nothing secret lives here. Wallet key material stays in
/// `Services/Keys/WalletKeyStore`, backed by the Keychain and the Secure
/// Enclave.
final class UserDefaultsPreferences: SyncPreferences, UiPreferences, NetworkPreferences, AppStatePreferences {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // MARK: - Network selection (global, not namespaced)
    // Android key: "selected_network"

    func getSelectedNetwork() -> NetworkType {
        guard let raw = defaults.string(forKey: Keys.selectedNetwork),
              let network = Self.networkType(named: raw) else {
            return .mainnet
        }
        return network
    }

    /// Must flush synchronously (shared contract): the caller may act on a
    /// process-restart-equivalent state change right after this returns.
    func setSelectedNetwork(network: NetworkType) {
        defaults.set(network.name, forKey: Keys.selectedNetwork)
        defaults.synchronize()
    }

    // MARK: - Sync mode
    // Android key: "sync_mode", namespaced per network / wallet.

    func getSyncModeOrNull(network: NetworkType?, walletId: String?) -> SyncMode? {
        let key = self.key(Keys.syncMode, network: network, walletId: walletId)
        guard let raw = defaults.string(forKey: key) else { return nil }
        return Self.syncMode(named: raw)
    }

    /// Defaults to `.newWallet` when nothing is explicitly stored. A fresh
    /// wallet has no past activity to find, and defaulting to `.recent` here
    /// would silently kick off an unrequested 30-day re-scan.
    func getSyncMode(network: NetworkType?, walletId: String?) -> SyncMode {
        getSyncModeOrNull(network: network, walletId: walletId) ?? .theNewWallet
    }

    func setSyncMode(mode: SyncMode, network: NetworkType?, walletId: String?) {
        let key = self.key(Keys.syncMode, network: network, walletId: walletId)
        defaults.set(mode.name, forKey: key)
    }

    // MARK: - Custom block height
    // Android key: "custom_block_height", namespaced per network / wallet.

    func getCustomBlockHeight(network: NetworkType?, walletId: String?) -> KotlinLong? {
        let key = self.key(Keys.customBlockHeight, network: network, walletId: walletId)
        guard let number = defaults.object(forKey: key) as? NSNumber else { return nil }
        return KotlinLong(longLong: number.int64Value)
    }

    func setCustomBlockHeight(height: KotlinLong?, network: NetworkType?, walletId: String?) {
        let key = self.key(Keys.customBlockHeight, network: network, walletId: walletId)
        if let height {
            defaults.set(height.int64Value, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }

    // MARK: - Initial sync
    // Android key: "initial_sync_completed", namespaced per network / wallet.

    func hasCompletedInitialSync(network: NetworkType?, walletId: String?) -> Bool {
        defaults.bool(forKey: key(Keys.initialSyncCompleted, network: network, walletId: walletId))
    }

    func setInitialSyncCompleted(completed: Bool, network: NetworkType?, walletId: String?) {
        defaults.set(completed, forKey: key(Keys.initialSyncCompleted, network: network, walletId: walletId))
    }

    // MARK: - Zero-live-cell rescue rescan (#332 parity)
    // Android key: "zero_cell_rescan_done", namespaced per wallet+network.

    func isZeroCellRescanDone(walletId: String, network: NetworkType?) -> Bool {
        defaults.bool(forKey: walletKey(walletId, network: network, key: Keys.zeroCellRescanDone))
    }

    func setZeroCellRescanDone(walletId: String, network: NetworkType?) {
        defaults.set(true, forKey: walletKey(walletId, network: network, key: Keys.zeroCellRescanDone))
    }

    func clearZeroCellRescanDone(walletId: String, network: NetworkType?) {
        defaults.removeObject(forKey: walletKey(walletId, network: network, key: Keys.zeroCellRescanDone))
    }

    // MARK: - Background sync (global, not per-network)
    // Android keys: "background_sync_enabled", "last_synced_at_ms".
    // Default OFF, matching Android (#116): background sync needs an
    // explicit opt-in gated on OS permission, not a default-on switch.

    func isBackgroundSyncEnabled() -> Bool {
        defaults.bool(forKey: Keys.backgroundSyncEnabled)
    }

    func setBackgroundSyncEnabled(enabled: Bool) {
        defaults.set(enabled, forKey: Keys.backgroundSyncEnabled)
        defaults.synchronize()
    }

    /// Wall-clock of the last observed sync progress (#286 parity). 0 = never synced.
    func getLastSyncedAt() -> Int64 {
        Int64(defaults.integer(forKey: Keys.lastSyncedAt))
    }

    func setLastSyncedAt(timestampMs: Int64) {
        defaults.set(timestampMs, forKey: Keys.lastSyncedAt)
    }

    // MARK: - Sync strategy (M3 multi-wallet)
    // Android key: "sync_strategy". Default ALL_WALLETS.

    func getSyncStrategy() -> SyncStrategy {
        guard let raw = defaults.string(forKey: Keys.syncStrategy),
              let strategy = Self.syncStrategy(named: raw) else {
            return .allWallets
        }
        return strategy
    }

    func setSyncStrategy(strategy: SyncStrategy) {
        defaults.set(strategy.name, forKey: Keys.syncStrategy)
    }

    // MARK: - Gap-limit signature detection (#382 parity)
    // Android keys: "gap_limit_signal", "gap_limit_banner_dismissed", namespaced per network / wallet.

    func isGapLimitSignalDetected(network: NetworkType?, walletId: String?) -> Bool {
        defaults.bool(forKey: key(Keys.gapLimitSignal, network: network, walletId: walletId))
    }

    func setGapLimitSignalDetected(detected: Bool, network: NetworkType?, walletId: String?) {
        defaults.set(detected, forKey: key(Keys.gapLimitSignal, network: network, walletId: walletId))
    }

    // MARK: - Theme
    // Android key: "theme_mode". Default SYSTEM.

    func getThemeMode() -> ThemeMode {
        guard let raw = defaults.string(forKey: Keys.themeMode),
              let mode = Self.themeMode(named: raw) else {
            return .system
        }
        return mode
    }

    func setThemeMode(mode: ThemeMode) {
        defaults.set(mode.name, forKey: Keys.themeMode)
    }

    // MARK: - Sync coachmark (first-run education, global)
    // Android key: "sync_coachmark_seen".

    func hasSeenSyncCoachmark() -> Bool {
        defaults.bool(forKey: Keys.syncCoachmarkSeen)
    }

    func markSyncCoachmarkSeen() {
        defaults.set(true, forKey: Keys.syncCoachmarkSeen)
    }

    // MARK: - Home staleness pill (#286 parity) — dismissal is permanent, not a nag.
    // Android key: "bg_sync_pill_dismissed".

    func isBgSyncPillDismissed() -> Bool {
        defaults.bool(forKey: Keys.bgSyncPillDismissed)
    }

    func setBgSyncPillDismissed() {
        defaults.set(true, forKey: Keys.bgSyncPillDismissed)
    }

    func isGapLimitBannerDismissed(network: NetworkType?, walletId: String?) -> Bool {
        defaults.bool(forKey: key(Keys.gapLimitBannerDismissed, network: network, walletId: walletId))
    }

    func setGapLimitBannerDismissed(network: NetworkType?, walletId: String?) {
        defaults.set(true, forKey: key(Keys.gapLimitBannerDismissed, network: network, walletId: walletId))
    }

    // MARK: - Bulk send (founder easter egg)
    // Android keys: "bulk_send_unlocked", "bulk_tx_hashes".

    func isBulkSendUnlocked() -> Bool {
        defaults.bool(forKey: Keys.bulkSendUnlocked)
    }

    func setBulkSendUnlocked(unlocked: Bool) {
        defaults.set(unlocked, forKey: Keys.bulkSendUnlocked)
    }

    func isBulkTxHash(hash: String) -> Bool {
        (defaults.array(forKey: Keys.bulkTxHashes) as? [String])?.contains(hash) == true
    }

    func addBulkTxHash(hash: String) {
        let current = (defaults.array(forKey: Keys.bulkTxHashes) as? [String]) ?? []
        guard !current.contains(hash) else { return }
        defaults.set(current + [hash], forKey: Keys.bulkTxHashes)
    }

    // MARK: - Active wallet (M3 multi-wallet)
    // Android key: "active_wallet_id".

    func getActiveWalletId() -> String? {
        defaults.string(forKey: Keys.activeWalletId)
    }

    func setActiveWalletId(walletId: String) {
        defaults.set(walletId, forKey: Keys.activeWalletId)
    }

    func clearActiveWalletId() {
        defaults.removeObject(forKey: Keys.activeWalletId)
    }

    // MARK: - App version (#370 parity)
    // Android key: "last_seen_version_code". 0 = never recorded (fresh install).

    func getLastSeenVersionCode() -> Int32 {
        Int32(defaults.integer(forKey: Keys.lastSeenVersionCode))
    }

    func setLastSeenVersionCode(code: Int32) {
        defaults.set(Int(code), forKey: Keys.lastSeenVersionCode)
        defaults.synchronize()
    }

    // MARK: - Database maintenance
    // Android key: "last_vacuum_at".

    func getLastVacuumAt() -> Int64 {
        Int64(defaults.integer(forKey: Keys.lastVacuumAt))
    }

    func setLastVacuumAt(timestampMs: Int64) {
        defaults.set(timestampMs, forKey: Keys.lastVacuumAt)
    }

    // MARK: - Per-network / per-wallet key helpers

    private func key(_ base: String, network: NetworkType?, walletId: String?) -> String {
        let net = network ?? getSelectedNetwork()
        if let walletId {
            return walletKey(walletId, network: net, key: base)
        }
        return "\(net.name.lowercased())_\(base)"
    }

    private func walletKey(_ walletId: String, network: NetworkType?, key: String) -> String {
        let net = network ?? getSelectedNetwork()
        return "\(walletId)_\(net.name.lowercased())_\(key)"
    }

    // MARK: - Enum <-> Kotlin name lookups
    // `NetworkType`, `SyncMode`, `SyncStrategy` and `ThemeMode` are Kotlin
    // enum classes bridged in as `KotlinEnum` subclasses; `.name` is the
    // original Kotlin constant name (e.g. "NEW_WALLET"), independent of the
    // Swift-visible case label (e.g. `.theNewWallet`).

    private static func networkType(named name: String) -> NetworkType? {
        NetworkType.entries.first { $0.name == name }
    }

    private static func syncMode(named name: String) -> SyncMode? {
        SyncMode.entries.first { $0.name == name }
    }

    private static func syncStrategy(named name: String) -> SyncStrategy? {
        SyncStrategy.entries.first { $0.name == name }
    }

    private static func themeMode(named name: String) -> ThemeMode? {
        ThemeMode.entries.first { $0.name == name }
    }

    // MARK: - Key names
    // One place per key string, mirroring the `companion object` constants in
    // `WalletPreferences.kt`. No migration constants here: iOS has no legacy
    // un-namespaced prefs to migrate from.

    private enum Keys {
        static let selectedNetwork = "selected_network"
        static let lastSeenVersionCode = "last_seen_version_code"
        static let syncMode = "sync_mode"
        static let customBlockHeight = "custom_block_height"
        static let initialSyncCompleted = "initial_sync_completed"
        static let zeroCellRescanDone = "zero_cell_rescan_done"
        static let activeWalletId = "active_wallet_id"
        static let syncStrategy = "sync_strategy"
        static let themeMode = "theme_mode"
        static let bulkSendUnlocked = "bulk_send_unlocked"
        static let bulkTxHashes = "bulk_tx_hashes"
        static let backgroundSyncEnabled = "background_sync_enabled"
        static let lastSyncedAt = "last_synced_at_ms"
        static let bgSyncPillDismissed = "bg_sync_pill_dismissed"
        static let gapLimitSignal = "gap_limit_signal"
        static let gapLimitBannerDismissed = "gap_limit_banner_dismissed"
        static let lastVacuumAt = "last_vacuum_at"
        static let syncCoachmarkSeen = "sync_coachmark_seen"
    }
}
