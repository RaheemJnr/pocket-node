import Foundation
import PocketNodeCore

/// The four formatters the activity list needs that the shared core cannot
/// supply: three of them are calendar-relative, and `commonMain` has no
/// calendar; the fourth is a plain string truncation kept beside them.
enum ActivityFormat {

    /// Milliseconds since the epoch from a CKB block timestamp, which is a
    /// `0x`-prefixed hex string of milliseconds. Decimal strings are accepted
    /// too, as Android's `formatBlockTimestamp` accepts them.
    ///
    /// Nil for a missing, zero or unparseable value, which is the case the two
    /// callers below both turn into "no time yet".
    static func blockTimestampMillis(_ raw: String?) -> Int64? {
        guard let raw, !raw.isEmpty, raw != "0x0", raw != "0" else { return nil }
        let digits: Substring
        if raw.hasPrefix("0x") || raw.hasPrefix("0X") {
            digits = raw.dropFirst(2)
        } else {
            digits = Substring(raw)
        }
        let radix = digits == Substring(raw) ? 10 : 16
        guard let value = Int64(digits, radix: radix), value > 0 else { return nil }
        return value
    }

    /// The uppercase group header a row sits under: TODAY, YESTERDAY, EARLIER,
    /// or PENDING for a row with no block timestamp.
    ///
    /// Three buckets rather than a date per day, which is what Android does:
    /// the wallet's history is short enough that a header per day would be
    /// mostly headers.
    static func dateGroup(
        blockTimestampHex: String?,
        now: Date = Date(),
        calendar: Calendar = .current
    ) -> String {
        guard let millis = blockTimestampMillis(blockTimestampHex) else { return "PENDING" }
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        if calendar.isDate(date, inSameDayAs: now) { return "TODAY" }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now),
           calendar.isDate(date, inSameDayAs: yesterday) {
            return "YESTERDAY"
        }
        return "EARLIER"
    }

    /// A block timestamp as the row's right-hand caption: the time alone for
    /// today, month and day within this year, the full date before that. The
    /// same three cases the Android `formatBlockTimestamp` picks between.
    ///
    /// The placeholder is "Pending" rather than Android's em dash, which reads
    /// as missing data rather than as a transaction that has not landed.
    static func blockTimestamp(
        _ raw: String?,
        now: Date = Date(),
        calendar: Calendar = .current,
        locale: Locale = .current,
        timeZone: TimeZone = .current
    ) -> String {
        guard let millis = blockTimestampMillis(raw) else { return "Pending" }
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone
        if calendar.isDate(date, inSameDayAs: now) {
            formatter.dateFormat = "HH:mm"
        } else if calendar.component(.year, from: date) == calendar.component(.year, from: now) {
            formatter.setLocalizedDateFormatFromTemplate("MMMd")
        } else {
            formatter.setLocalizedDateFormatFromTemplate("MMMdyyyy")
        }
        return formatter.string(from: date)
    }

    /// A hex block number as grouped decimal, e.g. `"0x1170ea8"` -> `"18,288,296"`.
    /// Anything unparseable comes back untouched rather than as a wrong number.
    static func blockNumber(_ raw: String) -> String {
        guard let value = Hex.parse(raw) else { return raw }
        return Int64(value).formatted(.number.grouping(.automatic))
    }

    /// `"0x1234567890...abcdef"`. The same 10 and 6 Android truncates to, and
    /// the same rule that a hash short enough to read whole is left whole.
    static func truncateHash(_ hash: String) -> String {
        guard hash.count > 20 else { return hash }
        return "\(hash.prefix(10))...\(hash.suffix(6))"
    }

    /// The transaction's page on the CKB explorer, per network.
    static func explorerURL(txHash: String, network: NetworkType) -> URL? {
        let base = network == NetworkType.mainnet
            ? "https://explorer.nervos.org/transaction"
            : "https://testnet.explorer.nervos.org/transaction"
        return URL(string: "\(base)/\(txHash)")
    }
}
