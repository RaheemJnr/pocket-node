package com.rjnr.pocketnode.data.send

import com.rjnr.pocketnode.data.gateway.models.Transaction

fun interface SubmissionAuthorization { suspend fun requireCurrent() }
fun interface InputSubmissionAuthorizer {
    suspend fun authorize(context: SendContext, transaction: Transaction): SubmissionAuthorization
}

/** Explicit platform capability: iOS has no recovery adoption or durable recovery guards. */
object NoDaoRecoveryAdoption : InputSubmissionAuthorizer {
    override suspend fun authorize(context: SendContext, transaction: Transaction) = SubmissionAuthorization { }
}
