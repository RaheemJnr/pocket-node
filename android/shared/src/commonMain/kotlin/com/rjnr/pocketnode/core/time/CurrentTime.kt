package com.rjnr.pocketnode.core.time

/**
 * Wall-clock milliseconds since the Unix epoch.
 *
 * `System.currentTimeMillis()` is JVM-only, so shared code that needs "now" (e.g.
 * `TransactionRecord.getRelativeTimeString()`) goes through this seam instead (#455).
 */
expect fun currentTimeMillis(): Long
