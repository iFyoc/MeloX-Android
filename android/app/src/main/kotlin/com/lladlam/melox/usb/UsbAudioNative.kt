package com.lladlam.melox.usb

@androidx.annotation.Keep
internal object UsbAudioNative {
    init { System.loadLibrary("melox_usb_audio") }
    external fun speed(fd: Int): Int
    external fun open(fd: Int, endpoint: Int, sampleRate: Int, maxPacketSize: Int, packetsPerSecond: Int, frameSize: Int): Long
    external fun offer(handle: Long, pcm: ByteArray): Boolean
    external fun failure(handle: Long): Int
    external fun ready(handle: Long): Boolean
    external fun setPaused(handle: Long, paused: Boolean)
    external fun flush(handle: Long)
    external fun stop(handle: Long)
    external fun restore(fd: Int, interfaceNumber: Int)
}
