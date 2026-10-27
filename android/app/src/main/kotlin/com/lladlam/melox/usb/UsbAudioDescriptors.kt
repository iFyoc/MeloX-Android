package com.lladlam.melox.usb

/** A UAC1 stereo PCM alternate setting with an adaptive or synchronous OUT endpoint. */
internal data class UsbAudioStream(
    val interfaceNumber: Int,
    val alternate: Int,
    val endpoint: Int,
    val maxPacketSize: Int,
    val interval: Int,
    val bytesPerSample: Int,
    val bitResolution: Int,
    val rates: List<Int>,
    val rateRange: IntRange?,
    val frequencyControl: Boolean,
) {
    fun supports(rate: Int): Boolean = rate in rates || rateRange?.contains(rate) == true

    fun packetsPerSecond(speed: Int): Int? = when (speed) {
        // The existing full-speed profile uses one transaction per millisecond.
        2 -> if (interval == 1 && maxPacketSize <= 1023) 1000 else null
        // High-speed ISO bInterval is an exponent in 125 µs microframes.
        3 -> if (interval in 1..4 && maxPacketSize <= 1024) 8000 shr (interval - 1) else null
        else -> null
    }

    fun canPlay(rate: Int, speed: Int): Boolean {
        val packets = packetsPerSecond(speed) ?: return false
        return rate in 1..192_000 && supports(rate) &&
            ((rate.toLong() + packets - 1) / packets) * bytesPerSample * 2 <= maxPacketSize
    }
}

internal object UsbAudioDescriptors {
    fun parse(raw: ByteArray): List<UsbAudioStream> {
        fun u(at: Int) = raw[at].toInt() and 255
        fun triple(at: Int) = u(at) or (u(at + 1) shl 8) or (u(at + 2) shl 16)
        val result = mutableListOf<UsbAudioStream>()
        var number = -1; var alt = 0; var streaming = false
        var pcm = false; var format = false; var endpoint = 0; var maxPacket = 0
        var interval = 0; var bytesPerSample = 0; var bitResolution = 0; var dataEndpoint = false
        var rates = emptyList<Int>(); var range: IntRange? = null; var frequencyControl = false
        var configurations = 0
        fun flush() {
            if (streaming && pcm && format && alt > 0 && endpoint != 0 && (rates.isNotEmpty() || range != null)) {
                result += UsbAudioStream(number, alt, endpoint, maxPacket, interval, bytesPerSample, bitResolution, rates, range, frequencyControl)
            }
        }
        var at = 0
        while (at + 2 <= raw.size) {
            val length = u(at); val type = u(at + 1)
            if (length < 2 || at + length > raw.size) return emptyList()
            when (type) {
                2 -> { // Android's UsbDevice interfaces refer to the first configuration.
                    if (++configurations > 1) break
                }
                4 -> {
                    if (length < 9) return emptyList()
                    flush()
                    number = u(at + 2); alt = u(at + 3)
                    streaming = u(at + 5) == 1 && u(at + 6) == 2 && u(at + 7) == 0
                    pcm = false; format = false; endpoint = 0; maxPacket = 0
                    interval = 0; bytesPerSample = 0; bitResolution = 0; dataEndpoint = false
                    rates = emptyList(); range = null; frequencyControl = false
                }
                0x24 -> if (streaming && length >= 3) {
                    when (u(at + 2)) {
                        1 -> if (length >= 7) pcm = u(at + 5) == 1 && u(at + 6) == 0
                        2 -> if (length >= 8) {
                            bytesPerSample = u(at + 5); bitResolution = u(at + 6)
                            format = u(at + 3) == 1 && u(at + 4) == 2 &&
                                bytesPerSample in 2..4 && bitResolution in 16..(bytesPerSample * 8)
                            val count = u(at + 7)
                            if (count == 0) {
                                if (length < 14) return emptyList()
                                val low = triple(at + 8); val high = triple(at + 11)
                                if (low <= high && low > 0) range = low..high
                            } else {
                                if (length < 8 + count * 3) return emptyList()
                                rates = List(count) { triple(at + 8 + it * 3) }.filter { it > 0 }
                            }
                        }
                    }
                }
                5 -> if (streaming) {
                    if (length < 7) return emptyList()
                    val attributes = u(at + 3)
                    val sync = (attributes shr 2) and 3
                    dataEndpoint = false
                    // Asynchronous endpoints require a separate feedback clock driver.
                    if (u(at + 2) and 0x80 == 0 && attributes and 3 == 1 && sync in 2..3 &&
                        (attributes shr 4) and 3 == 0 && (length < 9 || u(at + 8) == 0)) {
                        val packet = u(at + 4) or (u(at + 5) shl 8)
                        // High-bandwidth multi-transaction endpoints need a different scheduler.
                        if (packet in 1..1024 && endpoint == 0) {
                            endpoint = u(at + 2)
                            maxPacket = packet; interval = u(at + 6); dataEndpoint = true
                        }
                    }
                }
                0x25 -> if (dataEndpoint && length >= 4 && u(at + 2) == 1) {
                    frequencyControl = u(at + 3) and 1 != 0
                }
            }
            at += length
        }
        flush()
        return result
    }
}
