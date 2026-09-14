package com.rjnr.pocketnode.util

import android.util.Log
import com.rjnr.pocketnode.core.log.Logger

/** Android-backed [Logger] implementation, delegating to [android.util.Log]. */
class AndroidLogger : Logger {
    override fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    override fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    override fun w(tag: String, msg: String, t: Throwable?) {
        if (t != null) {
            Log.w(tag, msg, t)
        } else {
            Log.w(tag, msg)
        }
    }

    override fun e(tag: String, msg: String, t: Throwable?) {
        if (t != null) {
            Log.e(tag, msg, t)
        } else {
            Log.e(tag, msg)
        }
    }
}
