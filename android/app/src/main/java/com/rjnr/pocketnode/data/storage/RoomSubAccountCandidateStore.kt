package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.database.dao.SubAccountCandidateDao
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Room binding for the shared [SubAccountCandidateStore] seam (M3 #3). */
@Singleton
class RoomSubAccountCandidateStore @Inject constructor(
    private val dao: SubAccountCandidateDao,
) : SubAccountCandidateStore {

    override suspend fun getForParent(parentId: String): List<SubAccountCandidateRecord> =
        dao.getForParent(parentId).map { it.toRecord() }

    override suspend fun updateRegisteredFrom(
        parentId: String,
        derivationPath: String,
        fromBlock: Long,
    ) = dao.updateRegisteredFrom(parentId, derivationPath, fromBlock)
}

private fun SubAccountCandidateEntity.toRecord() = SubAccountCandidateRecord(
    parentWalletId = parentWalletId,
    derivationPath = derivationPath,
    accountIndex = accountIndex,
    scriptArgs = scriptArgs,
    state = state,
    registeredFromBlock = registeredFromBlock,
)
