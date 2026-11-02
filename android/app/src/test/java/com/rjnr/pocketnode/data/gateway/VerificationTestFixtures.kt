package com.rjnr.pocketnode.data.gateway

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

fun readyVerificationGate(): VerificationCacheGate = mockk {
    every { isReady(any()) } returns true
    every { currentRevision() } returns 0L
    every { readyNetworks } returns MutableStateFlow(setOf("MAINNET", "TESTNET"))
}
