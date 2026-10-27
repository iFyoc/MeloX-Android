package com.lladlam.melox.usb

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UsbAudioDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sysfsFallbackRequiresMatchingBusDeviceAndVendorProduct() {
        val root = temporary.newFolder("usb")
        val device = File(root, "1-2").apply { mkdirs() }
        mapOf("busnum" to "1", "devnum" to "4", "idVendor" to "1234", "idProduct" to "abcd", "speed" to "480")
            .forEach { (name, value) -> File(device, name).writeText(value) }
        val found = UsbDeviceSpeed.resolve(-25, "/dev/bus/usb/001/004", 0x1234, 0xabcd, root)
        assertEquals(3, found.speed)
        assertEquals("sysfs", found.source)
        assertEquals(-25, found.ioctlResult)
        assertEquals(0, UsbDeviceSpeed.resolve(-25, "/dev/bus/usb/001/004", 0x1234, 0xabce, root).speed)
        assertEquals(0, UsbDeviceSpeed.resolve(-25, "/dev/bus/usb/001/005", 0x1234, 0xabcd, root).speed)
        assertEquals(0, UsbDeviceSpeed.resolve(-25, "unexpected-path", 0x1234, 0xabcd, root).speed)
        assertEquals(2, UsbDeviceSpeed.resolve(2, "/dev/bus/usb/001/004", 0x1234, 0xabcd, root).speed)
    }

    @Test fun diagnosticsIncludeAudioDescriptorsButNotStringsOrOtherInterfaces() {
        fun bytes(vararg ints: Int) = ints.map(Int::toByte).toByteArray()
        val raw = bytes(18, 1, 0, 2, 0, 0, 0, 64, 0x34, 0x12, 0xcd, 0xab, 0, 1, 1, 2, 3, 1) +
            bytes(9, 4, 1, 0, 0, 1, 1, 0, 0) + bytes(4, 0x24, 1, 0xaa) +
            bytes(9, 4, 2, 0, 0, 3, 0, 0, 0) + bytes(4, 0x24, 1, 0xbb) +
            bytes(4, 3, 0xcc, 0xdd)
        UsbAudioDiagnostics.begin(0x1234, 0xabcd)
        UsbAudioDiagnostics.describe(UsbSpeedResult(3, "usbfs", 3), 44100, 2, 2, raw)
        val report = UsbAudioDiagnostics.report
        assertTrue(report.contains("1234:abcd"))
        assertTrue(report.contains("04 24 01 aa"))
        assertFalse(report.contains("04 24 01 bb"))
        assertFalse(report.contains("04 03 cc dd"))
    }
}
