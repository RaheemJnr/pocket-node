import Foundation

/// The single blob stored in the Keychain for a wallet.
///
/// Version 2, written by every store since #557:
///
/// ```
/// | 0x02 | label length, 1 byte | wrapping key label | wrapped key length, 4 bytes big endian | wrapped data key | AES-GCM combined ciphertext |
/// ```
///
/// Version 1, written before that and still read:
///
/// ```
/// | 0x01 | wrapped key length, 4 bytes big endian | wrapped data key | AES-GCM combined ciphertext |
/// ```
///
/// The label in version 2 is the wrapping key's `kSecAttrApplicationLabel`
/// (for a P-256 key, a hash of its public half), recorded when the envelope
/// is written. It binds the envelope to the one key that can unwrap it, so
/// ``WalletKeyStore/keyHealth`` can tell, without a prompt, that the key
/// under the wrapper's tag is not that key any more. A version 1 envelope has
/// no label and falls back to an existence check.
///
/// One item rather than two so a write is a single Keychain operation: the
/// Keychain has no transaction spanning items, and a wrapped data key that has
/// drifted out of step with its ciphertext can never be decrypted again. The
/// leading version byte is there so a layout change can be recognised rather
/// than guessed at.
///
/// Neither half is secret to the Keychain: the ciphertext needs the data key,
/// and the data key needs the Secure Enclave. The label is public too.
enum WalletKeyEnvelope {
    /// The version 1 layout, with no key label.
    static let version: UInt8 = 0x01
    /// The version 2 layout, with the wrapping key's label.
    static let versionWithKeyLabel: UInt8 = 0x02

    /// The label written over a version 2 envelope's own while its keys are
    /// being replaced (``WalletKeyStore/replaceUnusableKeys(with:)``). A real
    /// `kSecAttrApplicationLabel` is a 20-byte hash, so this one byte can
    /// never match a key, and an envelope carrying it reads as invalidated
    /// whatever key sits under the tag.
    static let retiredKeyLabel = Data([0x00])

    /// 1 version byte + 4 length bytes.
    private static let headerSize = 5

    /// A wrapped P-256 ECIES blob is about 100 bytes. The ceiling only has to be
    /// loose enough to never reject a real one while rejecting a corrupt length
    /// before it is used to slice the buffer.
    private static let maxWrappedKeySize = 4096

    /// A key label is a 20-byte hash; the ceiling only guards the slice.
    private static let maxKeyLabelSize = 64

    /// An AES-GCM combined box is a 12-byte nonce plus a 16-byte tag at minimum.
    private static let minCiphertextSize = 28

    /// Builds a version 1 envelope (no key label). The bounds are the ones
    /// ``decode`` enforces, checked here too so this can never write a blob
    /// its own parser would reject: violating them is a programming error,
    /// not bad input.
    static func encode(wrappedDataKey: Data, ciphertext: Data) -> Data {
        checkBounds(wrappedDataKey: wrappedDataKey, ciphertext: ciphertext)
        var envelope = Data(capacity: headerSize + wrappedDataKey.count + ciphertext.count)
        envelope.append(version)
        appendBody(to: &envelope, wrappedDataKey: wrappedDataKey, ciphertext: ciphertext)
        return envelope
    }

    /// Builds a version 2 envelope carrying `keyLabel`, or a version 1 one
    /// when there is no label to record.
    static func encode(wrappedDataKey: Data, ciphertext: Data, keyLabel: Data?) -> Data {
        guard let keyLabel else { return encode(wrappedDataKey: wrappedDataKey, ciphertext: ciphertext) }
        checkBounds(wrappedDataKey: wrappedDataKey, ciphertext: ciphertext)
        precondition(
            (1...maxKeyLabelSize).contains(keyLabel.count),
            "key label of \(keyLabel.count) bytes is outside the envelope's bounds"
        )
        var envelope = Data(capacity: 2 + keyLabel.count + headerSize + wrappedDataKey.count + ciphertext.count)
        envelope.append(versionWithKeyLabel)
        envelope.append(UInt8(keyLabel.count))
        envelope.append(keyLabel)
        appendBody(to: &envelope, wrappedDataKey: wrappedDataKey, ciphertext: ciphertext)
        return envelope
    }

    /// Parses an envelope of either version, rejecting anything malformed.
    ///
    /// Every length is checked against the buffer before it is used to slice it,
    /// so a truncated or tampered item fails here rather than somewhere further
    /// in with a half-valid result. `keyLabel` is nil for version 1.
    static func decode(_ data: Data) throws -> (wrappedDataKey: Data, ciphertext: Data, keyLabel: Data?) {
        let bytes = Data(data)
        guard let first = bytes.first else { throw WalletKeyStoreError.corrupt }

        switch first {
        case version:
            let body = try decodeBody(bytes, from: 1)
            return (body.wrappedDataKey, body.ciphertext, nil)
        case versionWithKeyLabel:
            guard bytes.count > 2 else { throw WalletKeyStoreError.corrupt }
            let labelLength = Int(bytes[1])
            guard labelLength > 0, labelLength <= maxKeyLabelSize, 2 + labelLength < bytes.count else {
                throw WalletKeyStoreError.corrupt
            }
            let label = Data(bytes[2..<(2 + labelLength)])
            let body = try decodeBody(bytes, from: 2 + labelLength)
            return (body.wrappedDataKey, body.ciphertext, label)
        default:
            throw WalletKeyStoreError.corrupt
        }
    }

    /// The same envelope with its key label replaced by
    /// ``retiredKeyLabel``, so it reads as invalidated while a replacement
    /// is under way. Nil for a buffer that does not parse.
    static func retired(_ data: Data) -> Data? {
        guard let decoded = try? decode(data) else { return nil }
        return encode(wrappedDataKey: decoded.wrappedDataKey, ciphertext: decoded.ciphertext, keyLabel: retiredKeyLabel)
    }

    // MARK: - Internals

    private static func checkBounds(wrappedDataKey: Data, ciphertext: Data) {
        precondition(
            (1...maxWrappedKeySize).contains(wrappedDataKey.count),
            "wrapped data key of \(wrappedDataKey.count) bytes is outside the envelope's bounds"
        )
        precondition(
            ciphertext.count >= minCiphertextSize,
            "ciphertext of \(ciphertext.count) bytes is shorter than an AES-GCM box"
        )
    }

    private static func appendBody(to envelope: inout Data, wrappedDataKey: Data, ciphertext: Data) {
        withUnsafeBytes(of: UInt32(wrappedDataKey.count).bigEndian) { envelope.append(contentsOf: $0) }
        envelope.append(wrappedDataKey)
        envelope.append(ciphertext)
    }

    /// The length-prefixed wrapped key and the ciphertext, starting at
    /// `offset` in a buffer that starts at index 0.
    private static func decodeBody(_ bytes: Data, from offset: Int) throws -> (wrappedDataKey: Data, ciphertext: Data) {
        let lengthEnd = offset + 4
        guard bytes.count > lengthEnd else { throw WalletKeyStoreError.corrupt }

        let length = bytes[offset..<lengthEnd].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard length > 0, length <= UInt32(maxWrappedKeySize) else { throw WalletKeyStoreError.corrupt }

        let wrappedEnd = lengthEnd + Int(length)
        guard wrappedEnd <= bytes.count, bytes.count - wrappedEnd >= minCiphertextSize else {
            throw WalletKeyStoreError.corrupt
        }

        return (
            wrappedDataKey: Data(bytes[lengthEnd..<wrappedEnd]),
            ciphertext: Data(bytes[wrappedEnd...])
        )
    }
}
