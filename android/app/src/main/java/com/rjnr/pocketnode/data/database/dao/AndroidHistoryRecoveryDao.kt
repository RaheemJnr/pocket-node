package com.rjnr.pocketnode.data.database.dao

import androidx.room.Dao
import com.rjnr.pocketnode.data.storage.HistoryRecoveryRoomDao

/** App-owned Room implementation name; SQL and transactions stay in the shared contract. */
@Dao
interface AndroidHistoryRecoveryDao : HistoryRecoveryRoomDao
