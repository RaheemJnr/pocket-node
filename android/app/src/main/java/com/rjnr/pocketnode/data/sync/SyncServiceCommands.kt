package com.rjnr.pocketnode.data.sync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Start/stop commands for [SyncForegroundService].
 *
 * Exists to break the only type-level dependency cycle in the app (#460):
 * [SyncForegroundService] is an `@AndroidEntryPoint` that injects
 * `GatewayRepository`, and `GatewayRepository` used to call
 * `SyncForegroundService.start(context)` back. The repository now depends on
 * this type instead, and this type depends on neither side: the service is
 * addressed by [SERVICE_CLASS_NAME] through an explicit [ComponentName]
 * rather than by a class literal, so starting it creates no edge back into
 * the service's own dependency graph.
 *
 * The class name is pinned to the real service by `SyncServiceCommandsTest`,
 * and the service itself is kept by R8 because it is declared in the manifest.
 */
@Singleton
class SyncServiceCommands @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Equivalent to `context.startForegroundService(Intent(context, SyncForegroundService::class.java))`. */
    fun start() {
        context.startForegroundService(serviceIntent())
    }

    /** Equivalent to `context.stopService(Intent(context, SyncForegroundService::class.java))`. */
    fun stop() {
        context.stopService(serviceIntent())
    }

    private fun serviceIntent(): Intent =
        Intent().setComponent(ComponentName(context.packageName, SERVICE_CLASS_NAME))

    companion object {
        /**
         * Fully-qualified name of [SyncForegroundService]. Pinned to the
         * class by `SyncServiceCommandsTest`; the matching `<service>` entry
         * lives in `AndroidManifest.xml`.
         */
        const val SERVICE_CLASS_NAME = "com.rjnr.pocketnode.data.sync.SyncForegroundService"
    }
}
