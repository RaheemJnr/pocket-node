import XCTest

@testable import PocketNode

final class BackupQuizTests: XCTestCase {
    private let words12 = [
        "abandon", "ability", "able", "about", "above", "absent",
        "absorb", "abstract", "absurd", "abuse", "access", "accident",
    ]

    private let words24 = [
        "abandon", "ability", "able", "about", "above", "absent",
        "absorb", "abstract", "absurd", "abuse", "access", "accident",
        "account", "accuse", "achieve", "acid", "acoustic", "acquire",
        "across", "act", "action", "actor", "actress", "actual",
    ]

    func testGeneratesThreeDistinctPositions() {
        var rng = SeededRandomNumberGenerator(seed: 1)

        let prompts = BackupQuiz.generate(words: words12, rng: &rng)

        XCTAssertEqual(prompts.count, 3)
        XCTAssertEqual(Set(prompts.map(\.position)).count, 3, "positions must be distinct")
        for prompt in prompts {
            XCTAssertTrue((0..<words12.count).contains(prompt.position))
        }
    }

    func testEachPromptHasFourDistinctChoicesIncludingTheCorrectWord() {
        var rng = SeededRandomNumberGenerator(seed: 42)

        let prompts = BackupQuiz.generate(words: words12, rng: &rng)

        for prompt in prompts {
            XCTAssertEqual(prompt.choices.count, 4)
            XCTAssertEqual(Set(prompt.choices).count, 4, "choices must be distinct")
            XCTAssertTrue(prompt.choices.contains(prompt.correctWord))
        }
    }

    func testDecoysNeverEqualTheCorrectWord() {
        var rng = SeededRandomNumberGenerator(seed: 7)

        let prompts = BackupQuiz.generate(words: words12, rng: &rng)

        for prompt in prompts {
            let decoys = prompt.choices.filter { $0 != prompt.correctWord }
            XCTAssertEqual(decoys.count, 3)
            XCTAssertFalse(decoys.contains(prompt.correctWord))
        }
    }

    func testWorksForTwelveWords() {
        var rng = SeededRandomNumberGenerator(seed: 2)

        let prompts = BackupQuiz.generate(words: words12, rng: &rng)

        XCTAssertEqual(prompts.count, 3)
    }

    func testWorksForTwentyFourWords() {
        var rng = SeededRandomNumberGenerator(seed: 3)

        let prompts = BackupQuiz.generate(words: words24, rng: &rng)

        XCTAssertEqual(prompts.count, 3)
        for prompt in prompts {
            XCTAssertEqual(prompt.choices.count, 4)
            XCTAssertTrue(prompt.choices.contains(prompt.correctWord))
        }
    }

    func testDeterministicForTheSameSeed() {
        var rngA = SeededRandomNumberGenerator(seed: 99)
        var rngB = SeededRandomNumberGenerator(seed: 99)

        let promptsA = BackupQuiz.generate(words: words12, rng: &rngA)
        let promptsB = BackupQuiz.generate(words: words12, rng: &rngB)

        XCTAssertEqual(promptsA, promptsB)
    }

    func testDifferentSeedsCanProduceDifferentQuizzes() {
        var rngA = SeededRandomNumberGenerator(seed: 1)
        var rngB = SeededRandomNumberGenerator(seed: 2)

        let promptsA = BackupQuiz.generate(words: words12, rng: &rngA)
        let promptsB = BackupQuiz.generate(words: words12, rng: &rngB)

        XCTAssertNotEqual(promptsA, promptsB)
    }

    /// A phrase too short to supply 3 distinct decoys from itself must fall
    /// back to the BIP-39 wordlist rather than producing duplicate or missing
    /// choices.
    func testFallsBackToTheWordlistWhenThePhraseIsTooShort() {
        var rng = SeededRandomNumberGenerator(seed: 5)
        let shortPhrase = ["abandon", "ability"]

        let prompts = BackupQuiz.generate(words: shortPhrase, rng: &rng)

        XCTAssertEqual(prompts.count, 2)
        for prompt in prompts {
            XCTAssertEqual(prompt.choices.count, 4)
            XCTAssertEqual(Set(prompt.choices).count, 4)
            XCTAssertTrue(prompt.choices.contains(prompt.correctWord))
        }
    }

    func testEmptyWordsProducesNoPrompts() {
        var rng = SeededRandomNumberGenerator(seed: 1)

        let prompts = BackupQuiz.generate(words: [], rng: &rng)

        XCTAssertTrue(prompts.isEmpty)
    }
}
