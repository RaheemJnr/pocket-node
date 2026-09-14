package com.rjnr.pocketnode.util

/**
 * Log scrubbing for sensitive identifiers (#321).
 *
 * Release builds strip ALL android.util.Log calls via the proguard
 * `-assumenosideeffects` rule, so these helpers exist for DEBUG builds:
 * logcat on a tester device is readable through adb and bugreports, and
 * full addresses + amounts together form a payment record. Keeping a short
 * prefix/suffix preserves enough to correlate log lines during debugging
 * without exposing the full linkable value.
 */

/** Address-shaped values: keep an 8-char prefix and 5-char suffix. */
fun String.redactAddress(): String =
    if (length <= 16) this else "${take(8)}…${takeLast(5)}"

/** Hash-shaped values (tx hashes, block hashes): keep a 10-char prefix. */
fun String.redactHash(): String =
    if (length <= 10) this else "${take(10)}…"
