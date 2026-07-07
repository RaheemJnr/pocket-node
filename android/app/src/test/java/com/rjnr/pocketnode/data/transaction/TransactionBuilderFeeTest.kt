package com.rjnr.pocketnode.data.transaction

import com.rjnr.pocketnode.data.validation.NetworkValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransactionBuilderFeeTest {

    private val builder = TransactionBuilder(NetworkValidator())

    @Test
    fun `estimateTransferFee returns positive fee for 1 input 1 output`() {
        val fee = builder.estimateTransferFee(1, 1)
        assertTrue("Fee should be positive", fee > 0)
    }

    @Test
    fun `estimateTransferFee returns at least MIN_FEE`() {
        val fee = builder.estimateTransferFee(1, 1)
        assertTrue("Fee should be at least 1000 shannons", fee >= 1_000L)
    }

    @Test
    fun `estimateTransferFee for small tx is clamped to MIN_FEE`() {
        // Small transactions (< ~1000 bytes) get clamped to MIN_FEE = 1000
        val fee = builder.estimateTransferFee(1, 2)
        assertEquals(1_000L, fee)
    }

    @Test
    fun `estimateTransferFee increases with many inputs`() {
        // Use enough inputs that the estimated size exceeds 1000 bytes,
        // so the fee rises above MIN_FEE and starts differentiating
        val feeSmall = builder.estimateTransferFee(10, 2)
        val feeLarge = builder.estimateTransferFee(20, 2)
        assertTrue("More inputs should mean higher fee for large txs", feeLarge > feeSmall)
    }

    /**
     * Field regression (Alex, Telegram 2026-07): a ~100-input send serialized
     * to tx_size = 5300 bytes but the estimator produced fee = 4964 < the
     * node's min_fee = 5300 (1000 shannons/KW) — every send from his
     * fragmented mining wallet was rejected "low fee rate". The estimator
     * undercounted molecule witness dynvec offsets (4 bytes/input), which the
     * flat +100 buffer only absorbed for ~25 inputs.
     *
     * The invariant: estimated fee must be >= fee-for-actual-size for a
     * many-input tx. 5300 shannons for tx_size 5300 at rate 1000/KW is the
     * exact real-world floor his tx needed.
     */
    @Test
    fun `estimateTransferFee covers the node minimum for a 100-input transaction`() {
        val fee = builder.estimateTransferFee(inputCount = 100, outputCount = 2)
        assertTrue(
            "100-input fee must cover the observed 5300-shannon node minimum, got $fee",
            fee >= 5_300L
        )
    }

    @Test
    fun `estimateTransferFee never undershoots the size it implies`() {
        // fee/rate gives the size the fee can pay for; the estimator's own
        // implied size must always cover a conservative real-size model:
        // raw(77 + 4+44*in + 4+101*out + 4+8*out) + witnesses(8*in + 89)
        // + tx wrapper(12) + in-block offset(4).
        for (inputs in listOf(1, 2, 10, 50, 100, 200, 500)) {
            for (outputs in listOf(1, 2)) {
                val conservativeSize =
                    77 + (4 + 44 * inputs) + (4 + 101 * outputs) + (4 + 8 * outputs) +
                        (8 * inputs + 89) + 12 + 4
                val fee = builder.estimateTransferFee(inputs, outputs)
                val minFee = (conservativeSize.toLong() * 1000L + 999L) / 1000L
                assertTrue(
                    "fee for ($inputs in, $outputs out) = $fee must be >= $minFee",
                    fee >= minFee
                )
            }
        }
    }

    @Test
    fun `estimateTransferFee stays below DEFAULT_FEE for typical transactions`() {
        // Typical DAO deposit: 1-3 inputs, 2 outputs
        val fee = builder.estimateTransferFee(2, 2)
        assertTrue(
            "Fee for typical tx should be below default 100k shannons",
            fee < TransactionBuilder.DEFAULT_FEE
        )
    }
}
