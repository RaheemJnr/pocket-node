package com.rjnr.pocketnode

import android.content.Intent
import android.os.Bundle

/**
 * The exported activity accepts launcher intents, not Navigation's internal back-stack protocol.
 * There are no supported Activity extras. Drop the entire bundle without unparcelling caller
 * objects; preserve action/data/categories/flags. In-app QR scanning uses its own validated path.
 * Apply before FragmentActivity or a NavHost can inspect the intent, on both delivery paths.
 */
internal fun sanitizedEntryIntent(source: Intent): Intent =
    Intent(source).replaceExtras(null as Bundle?)
