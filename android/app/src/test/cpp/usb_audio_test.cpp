// Exercise the same packet scheduler and ring used in the Android JNI library.
#include "../../main/cpp/usb_audio.cpp"
#include <cassert>
#include <cstdio>

static void testSchedule(int rate, int period, int frameSize) {
    Stream stream(STDIN_FILENO, 1, rate, 1024, period, frameSize);
    Transfer transfer(stream.maximum * stream.packets, stream.packets);
    int frames = 0;
    for (int batch = 0; batch < 125; ++batch) {
        stream.fill(transfer);
        for (int i = 0; i < stream.packets; ++i) {
            const auto length = transfer.urb->iso_frame_desc[i].length;
            assert(length <= 1024 && length % frameSize == 0);
            frames += length / frameSize;
        }
    }
    assert(frames == rate);
    assert(stream.fractional == 0);
}

int main() {
    for (int period : {1000, 2000, 4000, 8000}) {
        for (int frame : {4, 6, 8}) {
            testSchedule(44100, period, frame);
            testSchedule(48000, period, frame);
        }
    }
    auto stream = std::make_shared<Stream>(STDIN_FILENO, 1, 48000, 192, 1000, 4);
    registry.emplace(123, stream);
    stream->ring[0] = 1; stream->ring[1] = 2; stream->ring[2] = 3; stream->ring[3] = 4;
    stream->count = 4;
    Transfer transfer(stream->maximum * stream->packets, stream->packets);
    Java_com_lladlam_melox_usb_UsbAudioNative_setPaused(nullptr, nullptr, 123, true);
    stream->fill(transfer);
    assert(stream->count == 4 && transfer.bytes[0] == 0);
    Java_com_lladlam_melox_usb_UsbAudioNative_setPaused(nullptr, nullptr, 123, false);
    stream->fill(transfer);
    assert(stream->count == 0 && transfer.bytes[0] == 1 && transfer.bytes[3] == 4);
    stream->count = 4;
    Java_com_lladlam_melox_usb_UsbAudioNative_flush(nullptr, nullptr, 123);
    assert(stream->count == 0 && stream->read == 0);
    Java_com_lladlam_melox_usb_UsbAudioNative_stop(nullptr, nullptr, 123);
    assert(Java_com_lladlam_melox_usb_UsbAudioNative_open(nullptr, nullptr, -1, 1, 48000, 24, 1000, 4) == 0);
    std::puts("USB packet scheduler and pause/seek tests passed");
}
