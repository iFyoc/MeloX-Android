package com.lladlam.melox.usb

import java.io.File
import java.util.Locale

internal data class UsbSpeedResult(val speed: Int, val source: String, val ioctlResult: Int)

internal object UsbDeviceSpeed {
    fun resolve(ioctlResult: Int, deviceName: String, vendor: Int, product: Int, sysfs: File = File("/sys/bus/usb/devices")): UsbSpeedResult {
        if (ioctlResult > 0) return UsbSpeedResult(ioctlResult, "usbfs", ioctlResult)
        val match = Regex("^/dev/bus/usb/(\\d{1,3})/(\\d{1,3})$").matchEntire(deviceName)
            ?: return UsbSpeedResult(0, "unknown", ioctlResult)
        val bus = match.groupValues[1].toInt()
        val device = match.groupValues[2].toInt()
        val speed = runCatching {
            sysfs.listFiles()?.firstNotNullOfOrNull { dir ->
                fun attr(name: String) = runCatching { File(dir, name).readText().trim() }.getOrNull()
                if (attr("busnum")?.toIntOrNull() != bus || attr("devnum")?.toIntOrNull() != device ||
                    attr("idVendor")?.toIntOrNull(16) != vendor || attr("idProduct")?.toIntOrNull(16) != product) {
                    null
                } else when (attr("speed")?.toDoubleOrNull()) {
                    1.5 -> 1
                    12.0 -> 2
                    480.0 -> 3
                    5000.0, 10000.0, 20000.0 -> 5
                    else -> null
                }
            }
        }.getOrNull()
        return UsbSpeedResult(speed ?: 0, if (speed == null) "unknown" else "sysfs", ioctlResult)
    }
}

/** Last attempt only; no serial numbers, string descriptors, or other interfaces are collected. */
internal object UsbAudioDiagnostics {
    @Volatile var report: String = ""
        private set

    fun begin(vendor: Int, product: Int) {
        report = "USB audio VID:PID=${"%04x:%04x".format(Locale.ROOT, vendor, product)}"
    }

    fun describe(speed: UsbSpeedResult, rate: Int, channels: Int, encoding: Int, raw: ByteArray) {
        val descriptors = StringBuilder()
        var offset = 0
        var audio = false
        var captured = 0
        while (offset + 2 <= raw.size && captured < 4096) {
            val length = raw[offset].toInt() and 0xff
            val type = raw[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) {
                descriptors.appendLine("(malformed descriptor at $offset)")
                break
            }
            if (type == 4) audio = length >= 9 && (raw[offset + 5].toInt() and 0xff) == 1
            val include = type == 1 && length == 18 || type == 2 ||
                (audio && type in listOf(4, 5, 0x24, 0x25))
            if (include && captured + length <= 4096) {
                // A device descriptor has string indexes only, not their contents.
                for (index in offset until offset + length) {
                    if (index > offset) descriptors.append(' ')
                    descriptors.append("%02x".format(Locale.ROOT, raw[index].toInt() and 0xff))
                }
                descriptors.appendLine()
                captured += length
            }
            offset += length
        }
        report += "\nSpeed=${speed.speed} (${speed.source}; usbfs=${speed.ioctlResult}), decoded=${rate}Hz/${channels}ch/encoding=$encoding" +
            "\nAudio descriptors (hex):\n$descriptors"
    }

    fun result(message: String) { report += "\nResult: $message" }
}
