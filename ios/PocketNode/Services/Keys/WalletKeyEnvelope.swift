import Foundation

/// The single blob stored in the Keychain for a wallet.
///
/// ```
/// | 0x01 | wrapped key length, 4 bytes big endian | wrapped data key | AES-GCM combined ciphertext |
/// ```
///
/// One item rather than two so a write is a single Keychain operation: the
/// Keychain has no transaction spanning items, and a wrapped data key that has
/// drifted out of step with its ciphertext can never be decrypted again. The
/// leading version byte is there so a future layout change can be recognised
/// rather than guessed at.
///
/// Neither half is secret to the Keychain: the ciphertext needs the data key,
/// and the data key needs the Secure Enclave.
enum WalletKeyEnvelope {
    static let version: UInt8 = 0x01

    /// 1 version byte + 4 length bytes.
    private static let headerSize = 5

    /// A wrapped P-256 ECIES blob is about 100 bytes. The ceiling only has to be
    /// loose enough to never reject a real one while rejecting a corrupt length
    /// before it is used to slice the buffer.
    private static let maxWrappedKeySize = 4096

    /// An AES-GCM combined box is a 12-byte nonce plus a 16-byte tag at minimum.
    private static let minCiphertextSize = 28

    static func encode(wrappedDataKey: Data, ciphertext: Data) -> Data {
        var envelope = Data(capacity: headerSize + wrappedDataKey.count + ciphertext.count)
        envelope.append(version)
        withUnsafeBytes(of: UInt32(wrappedDataKey.count).bigEndian) { envelope.append(contentsOf: $0) }
        envelope.append(wrappedDataKey)
        envelope.append(ciphertext)
        return envelope
    }

    /// Parses an envelope, rejecting anything malformed.
    ///
    /// Every length is checked against the buffer before it is used to slice it,
    /// so a truncated or tampered item fails here rather than somewhere further
    /// in with a half-valid result.
    static func decode(_ data: Data) throws -> (wrappedDataKey: Data, ciphertext: Data) {
        let bytes = Data(data)
        guard bytes.count > headerSize, bytes[0] == version else {
            throw WalletKeyStoreError.corrupt
        }

        let length = bytes[1..<headerSize].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard length > 0, length <= UInt32(maxWrappedKeySize) else { throw WalletKeyStoreError.corrupt }

        let wrappedEnd = headerSize + Int(length)
        guard wrappedEnd <= bytes.count, bytes.count - wrappedEnd >= minCiphertextSize else {
            throw WalletKeyStoreError.corrupt
        }

        return (
            wrappedDataKey: Data(bytes[headerSize..<wrappedEnd]),
            ciphertext: Data(bytes[wrappedEnd...])
        )
    }
}
