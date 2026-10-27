package com.lladlam.melox.usb

import org.junit.Assert.*
import org.junit.Test

class UsbAudioDescriptorsTest {
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private val face = bytes(9, 4, 1, 1, 1, 1, 2, 0, 0)
    private val general = bytes(7, 0x24, 1, 1, 1, 1, 0)
    private val format = bytes(14, 0x24, 2, 1, 2, 2, 16, 2, 0x44, 0xac, 0, 0x80, 0xbb, 0)
    private val endpoint = bytes(9, 5, 1, 9, 196, 0, 1, 0, 0)
    private val control = bytes(7, 0x25, 1, 1, 0, 0, 0)
    private fun fixture() = face + general + format + endpoint + control

    @Test fun discreteRatesAndFrequencyControlBelongToTheirInterface() {
        val streams = UsbAudioDescriptors.parse(fixture() + bytes(9, 4, 2, 0, 0, 3, 0, 0, 0))
        assertEquals(1, streams.size)
        val selected = streams.single()
        assertEquals(1, selected.interfaceNumber)
        assertEquals(1, selected.alternate)
        assertEquals(1, selected.endpoint)
        assertTrue(selected.frequencyControl)
        assertTrue(selected.supports(44100)); assertTrue(selected.supports(48000))
        assertFalse(selected.supports(96000))
    }
    @Test fun continuousRangeIncludesIntermediateRates() {
        val continuous = bytes(14, 0x24, 2, 1, 2, 2, 16, 0, 0x44, 0xac, 0, 0x80, 0xbb, 0)
        val stream = UsbAudioDescriptors.parse(face + general + continuous + endpoint + control).single()
        assertTrue(stream.supports(46000)); assertFalse(stream.supports(96000))
    }
    @Test fun unsupportedUac2AsyncFeedbackAndNonPcmDoNotGetClaimed() {
        val uac2 = face.copyOf().apply { this[7] = 0x20 }
        assertTrue(UsbAudioDescriptors.parse(uac2 + general + format + endpoint).isEmpty())
        val async = endpoint.copyOf().apply { this[3] = 5 }
        assertTrue(UsbAudioDescriptors.parse(face + general + format + async).isEmpty())
        val compressed = general.copyOf().apply { this[5] = 2 }
        assertTrue(UsbAudioDescriptors.parse(face + compressed + format + endpoint).isEmpty())
        val invalidPcm = format.copyOf().apply { this[5] = 2; this[6] = 24 }
        assertTrue(UsbAudioDescriptors.parse(face + general + invalidPcm + endpoint).isEmpty())
    }
    @Test fun truncatedAndMalformedDescriptorsNeverReadOutOfBounds() {
        for (length in 1 until fixture().size) {
            UsbAudioDescriptors.parse(fixture().copyOf(length))
        }
        assertTrue(UsbAudioDescriptors.parse(bytes(9, 4, 1)).isEmpty())
        assertTrue(UsbAudioDescriptors.parse(bytes(2, 0x24)).isEmpty())
        assertTrue(UsbAudioDescriptors.parse(face + general + format.copyOf().apply { this[7] = 30 } + endpoint).isEmpty())
        assertTrue(UsbAudioDescriptors.parse(face + general + bytes(11, 0x24, 2, 1, 2, 2, 16, 0, 0, 0, 0)).isEmpty())
    }
    @Test fun absentPcmGeneralDescriptorAndInputOnlyEndpointsAreRejected() {
        assertTrue(UsbAudioDescriptors.parse(face + format + endpoint).isEmpty())
        val input = endpoint.copyOf().apply { this[2] = 0x81.toByte() }
        assertTrue(UsbAudioDescriptors.parse(face + general + format + input).isEmpty())
    }
    @Test fun attenuationRespectsSignedLittleEndianAndDoesNotMutateSource() {
        val source = bytes(0xff, 0x7f, 0, 0x80)
        assertArrayEquals(source, UsbPcm.scale(source, 1f))
        assertArrayEquals(bytes(0xff, 0x3f, 0, 0xc0), UsbPcm.scale(source, .5f))
        assertArrayEquals(bytes(0, 0, 0, 0), UsbPcm.scale(source, 0f))
        assertArrayEquals(bytes(0xff, 0x7f, 0, 0x80), source)
    }

    @Test fun fullAndHighSpeedUseTheirActualPacketPeriodsAndCapacity() {
        val wide = format.copyOf().apply { this[5] = 3; this[6] = 24 }
        val microframe = endpoint.copyOf().apply { this[4] = 36; this[6] = 1 }
        val stream = UsbAudioDescriptors.parse(face + general + wide + microframe + control).single()
        assertEquals(8000, stream.packetsPerSecond(3))
        assertTrue(stream.canPlay(44100, 3))
        assertTrue(stream.canPlay(48000, 3))
        assertFalse(stream.canPlay(48000, 2))
        assertFalse(stream.canPlay(48000, 0))
        assertFalse(stream.canPlay(96000, 3))
        val oneMillisecond = endpoint.copyOf().apply { this[4] = 0x40; this[5] = 1; this[6] = 4 }
        val slower = UsbAudioDescriptors.parse(face + general + wide + oneMillisecond).single()
        assertEquals(1000, slower.packetsPerSecond(3))
        assertTrue(slower.canPlay(48000, 3))
        assertNull(slower.packetsPerSecond(2))
    }

    @Test fun highBandwidthEndpointsAndOtherEndpointControlsAreNotMisidentified() {
        val highBandwidth = endpoint.copyOf().apply { this[5] = 0x08 }
        assertTrue(UsbAudioDescriptors.parse(face + general + format + highBandwidth).isEmpty())
        val input = bytes(9, 5, 0x81, 0x11, 3, 0, 1, 0, 0)
        val stream = UsbAudioDescriptors.parse(face + general + format + endpoint + input + control).single()
        assertFalse(stream.frequencyControl)
    }

    @Test fun pcm16IsLeftAlignedWhenWidenedWithoutInventingPrecision() {
        val source = bytes(0xff, 0x7f, 0, 0x80)
        assertArrayEquals(bytes(0, 0xff, 0x7f, 0, 0, 0x80), UsbPcm.pack(source, 1f, 3))
        assertArrayEquals(bytes(0, 0, 0xff, 0x3f, 0, 0, 0, 0xc0), UsbPcm.pack(source, .5f, 4))
        assertArrayEquals(source, UsbPcm.pack(source, 1f, 2))
    }
}
