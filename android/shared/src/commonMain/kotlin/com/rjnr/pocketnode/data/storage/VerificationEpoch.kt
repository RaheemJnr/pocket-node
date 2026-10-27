package com.rjnr.pocketnode.data.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Validates the local native handshake. A missing/old bridge must fail closed. */
object VerificationEpoch {
    @Throws(Exception::class)
    fun requireGeneration(envelope: String?): String {
        val root = Json.parseToJsonElement(requireNotNull(envelope) { "Verification epoch unavailable" }).jsonObject
        require(root["error"] == null) { "Verification epoch rejected" }
        val result = requireNotNull(root["result"]).jsonObject
        val version = requireNotNull(result["version"]).jsonPrimitive
        require(!version.isString && version.intOrNull == 1) { "Unsupported verification epoch" }
        val token = requireNotNull(result["generation"]).jsonPrimitive
        require(token.isString) { "Invalid verification generation type" }
        val generation = token.content
        require(generation.length == 32 && generation.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid verification generation"
        }
        return generation
    }
}
