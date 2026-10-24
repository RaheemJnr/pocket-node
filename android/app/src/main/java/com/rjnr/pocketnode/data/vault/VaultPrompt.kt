package com.rjnr.pocketnode.data.vault

import androidx.fragment.app.FragmentActivity

/** Returned credential buffers are owned by the caller and must be cleared. */
interface VaultPrompt {
    suspend fun request(activity: FragmentActivity, title: String, message: String, confirm: Boolean): CharArray?
    suspend fun choose(activity: FragmentActivity, title: String, message: String, positiveLabel: String, negativeLabel: String): Boolean?
    suspend fun notify(activity: FragmentActivity, title: String, message: String)
    suspend fun requestLegacyPin(activity: FragmentActivity, title: String, message: String): CharArray?
}
