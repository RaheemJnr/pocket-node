import Foundation
import PocketNodeCore

/// Pure generator for the backup verification quiz: 3 distinct word
/// positions, each with 4 shuffled choices (the correct word plus 3 distinct
/// decoys).
///
/// Everything random comes through the caller's `rng`, so a seeded generator
/// makes the whole quiz deterministic for tests — no hidden `SystemRandomNumberGenerator`
/// anywhere in this file.
enum BackupQuiz {
    /// One verification question: which word belongs at `position`.
    struct Prompt: Equatable {
        /// 0-based index into the original word list.
        let position: Int
        /// The word that actually belongs at `position`.
        let correctWord: String
        /// All 4 choices, shuffled together — `correctWord` is always one of them.
        let choices: [String]
    }

    /// Builds up to 3 prompts for `words`.
    ///
    /// Mirrors Android's `MnemonicBackupViewModel.showWords`: 3 distinct
    /// positions (fewer only if `words` itself has fewer than 3 entries),
    /// sorted so the prompts read in phrase order.
    static func generate(words: [String], rng: inout some RandomNumberGenerator) -> [Prompt] {
        guard !words.isEmpty else { return [] }

        let promptCount = min(3, words.count)
        let positions = Array(words.indices).shuffled(using: &rng).prefix(promptCount).sorted()

        return positions.map { position in
            let correct = words[position]
            let choices = choices(correct: correct, words: words, rng: &rng)
            return Prompt(position: position, correctWord: correct, choices: choices)
        }
    }

    /// The correct word plus 3 distinct decoys, shuffled together.
    ///
    /// Decoys are drawn from the other words in the phrase first. A phrase
    /// too short (or too repetitive) to supply 3 distinct decoys tops up from
    /// the full BIP-39 English wordlist — real 12/24-word phrases never hit
    /// that path, but a throwaway test phrase can.
    private static func choices(correct: String, words: [String], rng: inout some RandomNumberGenerator) -> [String] {
        var seen: Set<String> = [correct]
        var decoys: [String] = []

        func fill(from pool: [String]) {
            for word in pool.shuffled(using: &rng) {
                guard decoys.count < 3 else { return }
                guard !seen.contains(word) else { continue }
                seen.insert(word)
                decoys.append(word)
            }
        }

        fill(from: words)
        if decoys.count < 3 {
            fill(from: Bip39.shared.WORDLIST)
        }

        var result = decoys + [correct]
        result.shuffle(using: &rng)
        return result
    }
}
