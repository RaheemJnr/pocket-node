import Foundation

/// Configuration eligibility only, never chain coverage or recovery completion.
/// Kept in the app container (removed on reinstall), scoped to network, wallet
/// identity/creation and native generation. New wallets get fresh UUIDs.
struct FreshSyncConfiguration {
    let directory: URL
    let network: String

    private struct Entry: Codable {
        let createdAt: Int64
        let generation: String
    }
    private struct Record: Codable {
        let version: Int
        let network: String
        var wallets: [String: Entry]
    }
    private var file: URL { directory.appendingPathComponent("fresh-sync-configuration-v1.json") }

    private func read() -> Record {
        guard let data = try? Data(contentsOf: file),
              let record = try? JSONDecoder().decode(Record.self, from: data),
              record.version == 1, record.network == network else {
            return Record(version: 1, network: network, wallets: [:])
        }
        return record
    }

    func wasUnconfigured(wallet: WalletRecord, generation: String) -> Bool {
        let entry = read().wallets[wallet.id]
        return entry?.createdAt == wallet.createdAt && entry?.generation == generation
    }

    /// Only the awaited gate calls this after observing no stored sync mode.
    /// An atomic write failure must keep that gate closed.
    func recordUnconfigured(wallet: WalletRecord, generation: String) throws {
        var record = read()
        record.wallets[wallet.id] = Entry(createdAt: wallet.createdAt, generation: generation)
        try JSONEncoder().encode(record).write(to: file, options: .atomic)
    }
}
