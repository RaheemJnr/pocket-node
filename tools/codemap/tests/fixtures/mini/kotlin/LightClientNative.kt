package com.example.mini

object LightClientNative {
    external fun nativeGetCells(prefix: String): Long

    external fun nativeMissingInRust(): Long
}
