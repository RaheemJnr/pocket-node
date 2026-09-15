import Foundation

/// The plaintext key material for one wallet.
///
/// The JSON shape is the one Android already writes under its V2 Keystore key
/// (`WalletKeyBundle` in `data/crypto/KeystoreV2MigrationHelper.kt`, produced by
/// `KeyManager.encodePlaintextBundle`): a lowercase unprefixed private key hex
/// string plus the optional space-joined BIP39 mnemonic. The field names match
/// the Kotlin ones on purpose, so a bundle is portable between the platforms.
///
/// This local Swift struct is temporary: #511 moves the model into the shared
/// Kotlin core and iOS will use that type instead. Until then, keep the field
/// names in step with the Kotlin declaration.
struct WalletKeyBundle: Codable, Equatable, Sendable {
    let privateKeyHex: String
    let mnemonic: String?

    init(privateKeyHex: String, mnemonic: String? = nil) {
        self.privateKeyHex = privateKeyHex
        self.mnemonic = mnemonic
    }
}
