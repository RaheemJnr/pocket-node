package com.rjnr.pocketnode.util

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Restarts the app process (ProcessPhoenix-style): the launcher activity is
 * started first, then this process is killed, so the OS visibly relaunches
 * the app on its normal cold-start route.
 *
 * Used after a local reset. The embedded light client cannot be
 * re-initialised in the same process, and the sync loop and other
 * singletons would keep stale references to wallets that no longer exist.
 * Never returns on a device.
 */
@Singleton
class AppRestarter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun restart() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
