package com.rjnr.pocketnode.core.molecule

/**
 * Minimal growable byte buffer: the `java.io.ByteArrayOutputStream` replacement used by the
 * molecule serializers, which cannot reference `java.io` from commonMain (#455).
 */
internal class ByteArrayBuilder(initialCapacity: Int = 32) {

    private var buffer = ByteArray(if (initialCapacity > 0) initialCapacity else 32)
    private var size = 0

    fun write(bytes: ByteArray) {
        ensureCapacity(size + bytes.size)
        bytes.copyInto(buffer, size)
        size += bytes.size
    }

    fun write(byte: Int) {
        ensureCapacity(size + 1)
        buffer[size++] = byte.toByte()
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var newSize = buffer.size * 2
        while (newSize < required) newSize *= 2
        buffer = buffer.copyOf(newSize)
    }
}
