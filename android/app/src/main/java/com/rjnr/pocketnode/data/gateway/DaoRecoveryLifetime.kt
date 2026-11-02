package com.rjnr.pocketnode.data.gateway

import javax.inject.Inject
import javax.inject.Singleton

/** Foreground/context lifetime of completed receipts, independent of DAO route ownership. */
@Singleton
class DaoRecoveryLifetime @Inject constructor() {
    private var foreground = false
    private var revision = 0L
    private val invalidators = mutableListOf<() -> Unit>()

    @Synchronized fun onStarted() { foreground = true }

    // MainActivity is the sole app activity. Navigation and PIN auth stay inside it;
    // rotation is not backgrounding. External activity/home transitions do revoke authority.
    @Synchronized fun onStopped(changingConfigurations: Boolean) {
        if (!changingConfigurations) {
            foreground = false
            invalidate()
        }
    }

    @Synchronized fun invalidate() {
        revision++
        invalidators.forEach { it() }
    }

    @Synchronized internal fun observeInvalidation(invalidate: () -> Unit) { invalidators += invalidate }
    @Synchronized internal fun capture(): Long { check(foreground) { "App is not foreground" }; return revision }
    @Synchronized internal fun requireCurrent(ticket: Long) {
        check(foreground && revision == ticket) { "Recovery context changed" }
    }
}
