package com.lladlam.melox.usb

internal object UsbPcm {
    /** Widen PCM16 by padding its least-significant bytes; this does not restore lost source precision. */
    fun pack(bytes: ByteArray, gain: Float, bytesPerSample: Int): ByteArray {
        require(bytesPerSample in 2..4 && bytes.size % 2 == 0)
        val scaled = scale(bytes, gain)
        if (bytesPerSample == 2) return scaled
        val result = ByteArray(scaled.size / 2 * bytesPerSample)
        for (sample in 0 until scaled.size / 2) {
            val output = sample * bytesPerSample + bytesPerSample - 2
            result[output] = scaled[sample * 2]
            result[output + 1] = scaled[sample * 2 + 1]
        }
        return result
    }

    /** Full volume preserves the tap bytes; lower volume is explicitly software attenuation. */
    fun scale(bytes: ByteArray, gain: Float): ByteArray {
        if (gain >= 1f) return bytes
        val result = bytes.copyOf()
        for (offset in 0 until bytes.size - 1 step 2) {
            val sample = ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8))
            val scaled = (sample * gain.coerceIn(0f, 1f)).toInt().coerceIn(-32768, 32767)
            result[offset] = scaled.toByte(); result[offset + 1] = (scaled shr 8).toByte()
        }
        return result
    }
}
