package com.rjnr.pocketnode.data.send

import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.transaction.*
import com.rjnr.pocketnode.data.validation.NetworkValidator
import com.rjnr.pocketnode.data.wallet.AddressUtils
import kotlin.test.*

class DestinationPolicyTest {
    private val sender = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "aa".repeat(20))
    private val from = AddressUtils.encode(sender, NetworkType.TESTNET)
    private val cells = listOf(Cell(OutPoint("0x" + "11".repeat(32), "0x0"), "0x${300_00000000L.toString(16)}", "0x1", sender))
    private val builder = TransactionBuilder(NetworkValidator())
    private fun recipients(script: Script) = listOf(RecipientOutput(AddressUtils.encode(script, NetworkType.TESTNET), 100_00000000L))

    @Test fun malformedBuiltinStillDecodesButCannotBePreparedOrReviewed() {
        for (script in listOf(sender.copy(hashType = "data"), sender.copy(hashType = "data1"),
            sender.copy(hashType = "data2"), sender.copy(args = "0x" + "bb".repeat(19)), sender.copy(args = "0x" + "bb".repeat(21)))) {
            val outputs = recipients(script)
            assertEquals(script, AddressUtils.decode(outputs.single().address))
            assertFailsWith<IllegalArgumentException> { builder.planTransfer(outputs, cells) }
            assertFailsWith<IllegalArgumentException> { builder.prepareMultiTransfer(from, outputs, cells, NetworkType.TESTNET) }
            assertFailsWith<IllegalArgumentException> { ReviewedTransfer("wallet", from, NetworkType.TESTNET, outputs, 1000, 1) }
        }
    }

    @Test fun customScriptRoundtripsAndPreservesReviewedOutputs() {
        val custom = sender.copy(codeHash = "0x" + "42".repeat(32), hashType = "data2", args = "0x010203")
        val outputs = recipients(custom)
        assertEquals(custom, AddressUtils.decode(outputs.single().address))
        val prepared = builder.prepareMultiTransfer(from, outputs, cells, NetworkType.TESTNET)
        ReviewedTransfer("wallet", from, NetworkType.TESTNET, outputs, 1000, 1).validatePrepared(prepared)
    }

    @Test fun supportedDefaultDestinationsRemainAccepted() {
        val outputs = recipients(sender.copy(args = "0x" + "bb".repeat(20)))
        val prepared = builder.prepareMultiTransfer(from, outputs, cells, NetworkType.TESTNET)
        ReviewedTransfer("wallet", from, NetworkType.TESTNET, outputs, 1000, 1).validatePrepared(prepared)
    }
    @Test fun longerLockArgsRequireTheirActualOccupiedCapacity() {
        val forms = listOf(
            sender to 61L,
            sender.copy(codeHash = "0x" + "42".repeat(32), args = "0x") to 61L,
            Script("0x5c5069eb0857efc65e1bca0c07df34c31663b3622fd3876c876320fc9634e2a8", "type", "0x" + "bb".repeat(28)) to 69L,
            Script("0x3419a1c09eb2567f6552ee7a8ecffd64155cffe0f1796e6e61ec088d740c1356", "type", "0x" + "bb".repeat(21)) to 62L,
            Script("0x3419a1c09eb2567f6552ee7a8ecffd64155cffe0f1796e6e61ec088d740c1356", "type", "0x" + "bb".repeat(22)) to 63L,
            sender.copy(codeHash = "0x" + "42".repeat(32), args = "0x" + "bb".repeat(40)) to 81L,
        )
        for ((script, minimumCkb) in forms) {
            val address = AddressUtils.encode(script, NetworkType.TESTNET)
            val tooSmall = listOf(RecipientOutput(address, minimumCkb * 100_000000L - 1))
            assertFailsWith<IllegalArgumentException> { builder.planTransfer(tooSmall, cells) }
            assertFailsWith<IllegalArgumentException> { builder.prepareMultiTransfer(from, tooSmall, cells, NetworkType.TESTNET) }
            assertFailsWith<IllegalArgumentException> { ReviewedTransfer("wallet", from, NetworkType.TESTNET, tooSmall, 1000, 1) }
            val enough = listOf(RecipientOutput(address, minimumCkb * 100_000000L))
            val prepared = builder.prepareMultiTransfer(from, enough, cells, NetworkType.TESTNET)
            ReviewedTransfer("wallet", from, NetworkType.TESTNET, enough, 1000, 1).validatePrepared(prepared)
        }
    }

    @Test fun recognizedFormsAndNetworkSpecificAcpUseAuthoritativeCombinations() {
        val multisig = "0x5c5069eb0857efc65e1bca0c07df34c31663b3622fd3876c876320fc9634e2a8"
        val acp = mapOf(NetworkType.MAINNET to "0xd369597ff47f29fbc0d47d2e3775370d1250b85140c670e4718af712983a2354",
            NetworkType.TESTNET to "0x3419a1c09eb2567f6552ee7a8ecffd64155cffe0f1796e6e61ec088d740c1356")
        for (network in NetworkType.entries) {
            val forms = listOf(sender to setOf(20), sender.copy(codeHash = multisig) to setOf(20, 28),
                sender.copy(codeHash = acp.getValue(network)) to setOf(20, 21, 22))
            for ((base, lengths) in forms) {
                for (length in 19..29) for (hashType in listOf("type", "data", "data1", "data2")) {
                    val script = base.copy(hashType = hashType, args = "0x" + "bb".repeat(length))
                    val address = AddressUtils.encode(script, network)
                    assertEquals(script, AddressUtils.decode(address))
                    val assessment = DestinationPolicy.assess(address, network)
                    assertEquals(if (hashType == "type" && length in lengths) DestinationKind.RECOGNIZED else DestinationKind.KNOWN_INVALID, assessment.kind)
                    assertEquals(hashType == "type" && length in lengths, assessment.canSend)
                }
            }
            val otherNetwork = if (network == NetworkType.TESTNET) NetworkType.MAINNET else NetworkType.TESTNET
            val foreignAcp = AddressUtils.encode(sender.copy(codeHash = acp.getValue(otherNetwork)), network)
            assertEquals(DestinationKind.KNOWN_INVALID, DestinationPolicy.assess(foreignAcp, network).kind)
            val otherAddress = AddressUtils.encode(sender, otherNetwork)
            assertEquals(DestinationKind.WRONG_NETWORK, DestinationPolicy.assess(otherAddress, network).kind)
        }
    }

    @Test fun customClassificationAndGuidanceAreFrozenPerReviewedRecipient() {
        val custom = sender.copy(codeHash = "0x" + "42".repeat(32), hashType = "data2")
        val outputs = (recipients(sender) + recipients(custom)).toMutableList()
        val reviewed = ReviewedTransfer("wallet", from, NetworkType.TESTNET, outputs, 1000, 1, true)
        outputs.clear()
        assertEquals(listOf(DestinationKind.RECOGNIZED, DestinationKind.CUSTOM), reviewed.destinationAssessments.map { it.kind })
        assertTrue(reviewed.destinationAssessments[1].canSend)
        assertEquals(DestinationPolicy.CUSTOM_GUIDANCE, reviewed.destinationAssessments[1].message)
        runCatching { (reviewed.destinationAssessments as? MutableList<DestinationAssessment>)?.clear() }
        assertEquals(2, reviewed.destinationAssessments.size)
        reviewed.validatePrepared(builder.prepareMultiTransfer(from, reviewed.recipients, cells, NetworkType.TESTNET))
    }

    @Test fun legacyAddressesRemainRecognizedAndArbitraryHashTypesRemainCustom() {
        assertEquals(DestinationKind.RECOGNIZED, DestinationPolicy.assess(
            "ckb1qyqt8xaupvm8837nv3gtc9x0ekkj64vud3jqfwyw5v", NetworkType.MAINNET).kind)
        assertEquals(DestinationKind.RECOGNIZED, DestinationPolicy.assess(
            "ckb1qyq5lv479ewscx3ms620sv34pgeuz6zagaaqklhtgg", NetworkType.MAINNET).kind)
        for (hashType in listOf("type", "data", "data1", "data2")) {
            val custom = sender.copy(codeHash = "0x" + "42".repeat(32), hashType = hashType)
            val outputs = recipients(custom)
            val address = outputs.single().address
            assertEquals(custom, AddressUtils.decode(address))
            assertEquals(DestinationKind.CUSTOM, DestinationPolicy.assess(address, NetworkType.TESTNET).kind)
            val prepared = builder.prepareMultiTransfer(from, outputs, cells, NetworkType.TESTNET)
            ReviewedTransfer("wallet", from, NetworkType.TESTNET, outputs, 1000, 1).validatePrepared(prepared)
        }
    }

}
