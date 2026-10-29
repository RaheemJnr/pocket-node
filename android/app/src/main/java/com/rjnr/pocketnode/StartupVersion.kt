package com.rjnr.pocketnode

import com.rjnr.pocketnode.data.wallet.WalletPreferences

/**
 * Version bookkeeping performed before the startup PIN gate.
 * A normal signed upgrade is not authentication or consent to reset the wallet:
 * failed attempts and lockout deadlines remain in the PIN store across versions.
 */
internal fun recordStartupVersion(preferences: WalletPreferences, current: Int) {
    if (preferences.getLastSeenVersionCode() != current) {
        preferences.setLastSeenVersionCode(current)
    }
}
