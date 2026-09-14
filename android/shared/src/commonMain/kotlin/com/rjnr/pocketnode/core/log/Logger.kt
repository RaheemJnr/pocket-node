package com.rjnr.pocketnode.core.log

/**
 * Logging seam for the shared KMP module.
 *
 * `android.util.Log` is Android-only and cannot be referenced from `commonMain`, so classes
 * that live in `shared` (e.g. `TransactionBuilder`) take a [Logger] as a constructor
 * dependency instead of calling a platform log API directly. Platform implementations are
 * injected by each platform's DI: Hilt provides one on Android, `AppContainer` on iOS.
 */
interface Logger {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)
}

/** No-op [Logger] for tests and other contexts where log output is not needed. */
object NoopLogger : Logger {
    override fun d(tag: String, msg: String) {}
    override fun i(tag: String, msg: String) {}
    override fun w(tag: String, msg: String, t: Throwable?) {}
    override fun e(tag: String, msg: String, t: Throwable?) {}
}
