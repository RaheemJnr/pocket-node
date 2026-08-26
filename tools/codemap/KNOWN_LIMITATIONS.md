# Known limitations

## Grammar

**tree-sitter-kotlin 1.1.0 one-line bodies.** `interface I { fun m() }` written on a
single line reports a parse error; the multi-line form is fine. No real file in this
repo uses that style. Keep test fixtures multi-line.

**WalletKeyReader.kt reports a parse error (1 of 178 Kotlin files).** Bisecting
implicates line 291, `java.util.Arrays.fill(sessionPin, ' ')` inside a
`launch { try/catch/finally }`, but the construct parses cleanly when isolated, so
the trigger is a contextual grammar edge case rather than that line alone. Impact is
contained: tree-sitter error recovery still yields 18 of the file's declarations,
its package, and all 12 imports. The file appears in the map with partial members
rather than disappearing. Revisit if the grammar is upgraded.

## Call edges

`calls` edges are heuristic. See the README for the measured unresolved rate and the
constructs that are deliberately not resolved (generics, lambda receivers, scope
functions rebinding `this`, extension dispatch).
