package com.rjnr.pocketnode.data.transaction

import com.rjnr.pocketnode.data.gateway.models.Transaction
import com.rjnr.pocketnode.data.validation.NetworkValidator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Replays a real mainnet secp256k1 transfer through our own molecule encoder and
 * the shared Blake2b, and asserts the result is the hash the chain recorded.
 *
 * This is the end-to-end anchor for the CKB Java SDK swap (#454): a wrong
 * personalization, a wrong hex decode, or a wrong field order in the raw
 * transaction table all change this hash. The known-answer and differential
 * tests in :shared prove the primitives in isolation; this proves them wired
 * into the real serialiser on real data.
 *
 * Fixture: CKB mainnet transaction
 * 0x36d8bc6e05364ac68dc24e1bfd8b021045a591d571fdcf1d3d84b2b33a5eb0a8
 * in block 20454481 (0x1381c51), fetched with
 *   curl -X POST https://mainnet.ckb.dev/rpc -H 'Content-Type: application/json' \
 *     -d '{"id":1,"jsonrpc":"2.0","method":"get_transaction",
 *          "params":["0x36d8bc6e05364ac68dc24e1bfd8b021045a591d571fdcf1d3d84b2b33a5eb0a8"]}'
 * One input, two secp256k1-blake160 outputs, no type scripts, no header deps.
 */
class MainnetTxHashReplayTest {

    private val builder = TransactionBuilder(networkValidator = NetworkValidator())

    private val json = Json { ignoreUnknownKeys = true }

    private val onChainHash = "0x36d8bc6e05364ac68dc24e1bfd8b021045a591d571fdcf1d3d84b2b33a5eb0a8"

    private val onChainJson = """
        {
          "version": "0x0",
          "cell_deps": [
            {
              "out_point": {
                "tx_hash": "0x71a7ba8fc96349fea0ed3a5c47992e3b4084b031a42264a018e0072e8172e46c",
                "index": "0x0"
              },
              "dep_type": "dep_group"
            }
          ],
          "header_deps": [],
          "inputs": [
            {
              "since": "0x0",
              "previous_output": {
                "tx_hash": "0x5db5e03c9014939a93384b7713aec935890801823621677152e007eb7ed09d14",
                "index": "0x0"
              }
            }
          ],
          "outputs": [
            {
              "capacity": "0xf8b12895d",
              "lock": {
                "code_hash": "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
                "hash_type": "type",
                "args": "0x569dde8487b0a1486c04b7bff4dfa94b12fa65b9"
              },
              "type": null
            },
            {
              "capacity": "0x21ef93ace",
              "lock": {
                "code_hash": "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
                "hash_type": "type",
                "args": "0x4a336470564d07ca7059b7980481c2d59809d637"
              },
              "type": null
            }
          ],
          "outputs_data": ["0x", "0x"],
          "witnesses": [
            "0x5500000010000000550000005500000041000000edafed32f8e828c7e06eb8537e07cb481a2135bd5880b81a5742928df58ed8450e2e636f86900d8a8014bee418de0eb98bc306d474b62ca1c0955a1401efe13600"
          ]
        }
    """.trimIndent()

    @Test
    fun `computeTxHash reproduces the on-chain hash of a real mainnet transfer`() {
        val tx = json.decodeFromString<Transaction>(onChainJson)
        assertEquals(onChainHash, builder.computeTxHash(tx))
    }

    @Test
    fun `computeTxHash ignores the witness on the real transaction too`() {
        // The tx hash covers the raw transaction only; the recorded signature
        // must not feed into it.
        val tx = json.decodeFromString<Transaction>(onChainJson)
        assertEquals(onChainHash, builder.computeTxHash(tx.copy(witnesses = listOf("0x"))))
    }
}
