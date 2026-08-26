package com.example.mini

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MiniRepository @Inject constructor(
    private val context: Context,
) {
    /** Fetches the live cell count through the bridge. */
    fun cellCount(): Long {
        return LightClientNative.nativeGetCells("addr")
    }

    fun pureHelper(value: Long): Long {
        return value * 2
    }
}
