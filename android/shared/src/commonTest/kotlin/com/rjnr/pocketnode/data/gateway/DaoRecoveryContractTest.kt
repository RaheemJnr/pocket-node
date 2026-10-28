package com.rjnr.pocketnode.data.gateway

import kotlin.test.*
import kotlinx.serialization.json.*

class DaoRecoveryContractTest {
    private val genesis = "0x" + "00".repeat(32)
    private val hash = "0x" + "11".repeat(32)
    private val identity = """{"genesis_hash":"$genesis","store_generation":"test-store","run_nonce":"0x${"07".repeat(16)}"}"""
    private val request = """{"schema_version":1,"operation":"request","result":{"request_id":"dr1.q.00","transaction_hash":"$hash","identity":$identity}}"""
    @Test fun requestRoundtripPinsContextAndDedicatedCalls() {
        val api = FakeLightClientApi().enqueue("requestDaoRecovery", request)
        val result = DaoRecoveryClient(api, genesis).request(hash)
        val ticket = assertIs<DaoRecoveryResult.Success<DaoRecoveryTicket>>(result).value
        assertEquals(hash, ticket.transactionHash)
        assertEquals(genesis, ticket.identity.genesisHash)
        assertEquals(listOf(FakeLightClientApi.Call("requestDaoRecovery", listOf(hash))), api.calls)
    }
    @Test fun malformedForeignAndErrorNeverCreateTicket() {
        val bad = listOf(null,"null","{}","garbage",
            request.replace("\"schema_version\":1", "\"schema_version\":2,\"schema_version\":1"),
            request.replace("\"schema_version\":1", "\"schema_version\":2,\"\\u0073chema_version\":1"),
            request.replace("\"store_generation\":\"test-store\"", "\"store_generation\":\"foreign\",\"store_generation\":\"test-store\""), request.replace("\"schema_version\":1", "\"schema_version\":2"),
            request.replace(hash, genesis), request.replace(genesis,"0x"+"22".repeat(32)),
            request.replace("dr1.q.00","dr1.q."+"aa".repeat(1100)), request.replace("dr1.q.00","dr2.q.00"),request.replace("dr1.q.00","dr1.r.00"),
            request.replace("\"request\"","\"poll\""),request.replace("test-store",""),
            """{"schema_version":1,"operation":"request","error":{"code":"capacity"}}""",
            """{"schema_version":1,"operation":"request","error":{"code":"future_code"}}""")
        bad.forEach { response ->
            val api = FakeLightClientApi().enqueue("requestDaoRecovery", response)
            assertIs<DaoRecoveryResult.Failure>(DaoRecoveryClient(api,genesis).request(hash), response)
        }
    }
    @Test fun pendingCancelledUnknownAndMismatchedPollFailClosed() {
        val api = FakeLightClientApi().enqueue("requestDaoRecovery",request)
        val client = DaoRecoveryClient(api,genesis)
        val ticket = assertIs<DaoRecoveryResult.Success<DaoRecoveryTicket>>(client.request(hash)).value
        for (status in listOf("queued","fetching","unavailable","expired","cancelled")) {
            api.enqueue("pollDaoRecovery", """{"schema_version":1,"operation":"poll","result":{"request_id":"dr1.q.00","status":"$status","receipt":null}}""")
            assertEquals(status, assertIs<DaoRecoveryResult.Success<DaoRecoveryPoll>>(client.poll(ticket)).value.status.name.lowercase())
            api.reset()
        }
        for (response in listOf(null, "{}", """{"schema_version":1,"operation":"poll","result":{"request_id":"dr1.q.02","status":"verified","receipt":null}}""",
            """{"schema_version":1,"operation":"poll","result":{"request_id":"dr1.q.00","status":"future","receipt":null}}""")) {
            api.enqueue("pollDaoRecovery",response)
            assertIs<DaoRecoveryResult.Failure>(client.poll(ticket)); api.reset()
        }
    }
    @Test fun invalidValidationAndCancellationCannotAuthorize() {
        val api = FakeLightClientApi().enqueue("requestDaoRecovery",request)
        val client = DaoRecoveryClient(api,genesis)
        val ticket = assertIs<DaoRecoveryResult.Success<DaoRecoveryTicket>>(client.request(hash)).value
        for (response in listOf(null,"null","{}", """{"schema_version":1,"operation":"validate","result":{"request_id":"dr1.q.00","valid":false,"receipt":null}}""",
            """{"schema_version":1,"operation":"validate","error":{"code":"invalid_receipt"}}""")) {
            api.enqueue("validateDaoRecovery",response)
            assertIs<DaoRecoveryResult.Failure>(client.validate(ticket,"dr1.r.00")); api.reset()
        }
        api.enqueue("cancelDaoRecovery", """{"schema_version":1,"operation":"cancel","result":{"request_id":"dr1.q.00","status":"cancelled"}}""")
        assertIs<DaoRecoveryResult.Success<DaoRecoveryCancellation>>(client.cancel(ticket))
        assertTrue(api.calls.none {it.function=="callRpc"})
    }
    @Test fun nativeReceiptFixtureRoundtripAndIdentityFences() {
        val request = Json.parseToJsonElement(DaoRecoveryFixtures.REQUEST).jsonObject["result"]!!.jsonObject
        val nativeGenesis = request["identity"]!!.jsonObject["genesis_hash"]!!.jsonPrimitive.content
        val nativeHash = request["transaction_hash"]!!.jsonPrimitive.content
        val api = FakeLightClientApi().enqueue("requestDaoRecovery",DaoRecoveryFixtures.REQUEST)
            .enqueue("pollDaoRecovery",DaoRecoveryFixtures.POLL).enqueue("validateDaoRecovery",DaoRecoveryFixtures.VALIDATE)
        val client = DaoRecoveryClient(api,nativeGenesis)
        val ticket = assertIs<DaoRecoveryResult.Success<DaoRecoveryTicket>>(client.request(nativeHash)).value
        val receipt = assertNotNull(assertIs<DaoRecoveryResult.Success<DaoRecoveryPoll>>(client.poll(ticket)).value.receipt)
        val valid = assertIs<DaoRecoveryResult.Success<DaoRecoveryValidation>>(client.validate(ticket,receipt.receiptId)).value
        assertTrue(valid.valid); assertEquals(receipt,valid.receipt); assertNull(receipt.transactionIndex)
        assertEquals(ticket.identity, valid.receipt.identity)
        val envelope = Json.parseToJsonElement(DaoRecoveryFixtures.VALIDATE).jsonObject
        val result = envelope["result"]!!.jsonObject
        val proof = result["receipt"]!!.jsonObject
        val changes = mapOf(
            "schema_version" to JsonPrimitive(2), "proof_kind" to JsonPrimitive("witness_authentication"),
            "request_id" to JsonPrimitive("dr1.q.00"), "receipt_id" to JsonPrimitive("dr1.r.00"),
            "attempt_id" to JsonPrimitive("dr1.q.00"), "transaction_hash" to JsonPrimitive(hash),
            "revision" to JsonPrimitive("18446744073709551616"), "transaction_index" to JsonPrimitive(0),
            "header" to JsonObject(proof["header"]!!.jsonObject - "dao"),
            "raw_transaction" to JsonObject(proof["raw_transaction"]!!.jsonObject + ("witnesses" to JsonArray(emptyList())))
        )
        val raw = proof["raw_transaction"]!!.jsonObject
        val badRaw = listOf(
            JsonObject(raw - "version"),
            JsonObject(raw + ("outputs_data" to JsonArray(emptyList()))),
            JsonObject(raw + ("inputs" to JsonArray(listOf(JsonObject(raw["inputs"]!!.jsonArray[0].jsonObject - "since"))))),
            JsonObject(raw + ("outputs" to JsonArray(listOf(JsonObject(raw["outputs"]!!.jsonArray[0].jsonObject - "type")))))
        ).map { JsonObject(proof + ("raw_transaction" to it)) }
        val badProofs = badRaw + changes.map { (field,value) -> JsonObject(proof + (field to value)) } +
            proof.keys.map { JsonObject(proof - it) } +
            listOf("genesis_hash","store_generation","run_nonce").map { field ->
                JsonObject(proof + ("identity" to JsonObject(proof["identity"]!!.jsonObject + (field to JsonPrimitive("foreign")))))
            }
        badProofs.forEach { badProof ->
            val bad = JsonObject(envelope + ("result" to JsonObject(result + ("receipt" to badProof)))).toString()
            api.reset(); api.enqueue("validateDaoRecovery",bad)
            assertIs<DaoRecoveryResult.Failure>(client.validate(ticket,receipt.receiptId),bad)
        }
        api.reset(); api.enqueue("validateDaoRecovery",DaoRecoveryFixtures.VALIDATE)
        assertIs<DaoRecoveryResult.Failure>(client.validate(ticket,"dr1.r.00"))
        assertTrue(api.calls.none { it.function == "callRpc" })
    }
}
