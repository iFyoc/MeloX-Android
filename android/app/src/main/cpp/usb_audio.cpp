#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <poll.h>
#include <unistd.h>
#include <cerrno>
#include <atomic>
#include <algorithm>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {
constexpr int depth = 4;
struct Transfer {
    // usbdevfs_urb ends in a flexible array; allocate aligned storage for packet descriptors.
    std::unique_ptr<unsigned char[]> storage;
    std::vector<unsigned char> bytes;
    usbdevfs_urb* urb;
    bool pending = false;
    explicit Transfer(int capacity, int packets) : storage(new unsigned char[sizeof(usbdevfs_urb) + packets * sizeof(usbdevfs_iso_packet_desc)]()),
        bytes(capacity), urb(reinterpret_cast<usbdevfs_urb*>(storage.get())) {}
};
struct Stream {
    int fd;
    int endpoint;
    int rate;
    int maximum;
    int packetsPerSecond;
    int frameSize;
    int packets;
    std::atomic<bool> running{true};
    std::atomic<int> error{0};
    std::atomic<bool> ready{false};
    std::mutex lock;
    std::vector<unsigned char> ring;
    size_t read = 0, count = 0;
    bool paused = false;
    int fractional = 0;
    std::thread thread;
    Stream(int descriptor, int ep, int hz, int max, int period, int frame)
        : fd(dup(descriptor)), endpoint(ep), rate(hz), maximum(max), packetsPerSecond(period),
          frameSize(frame), packets(period / 125), ring(hz * frame / 2) {}
    ~Stream() { shutdown(); if (fd >= 0) close(fd); }
    void shutdown() { running = false; if (thread.joinable()) thread.join(); }
    void fill(Transfer& transfer) {
        auto* urb = transfer.urb;
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = endpoint;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = transfer.bytes.data();
        urb->number_of_packets = packets;
        urb->usercontext = &transfer;
        int size = 0;
        for (int i = 0; i < packets; ++i) {
            fractional += rate;
            const int length = (fractional / packetsPerSecond) * frameSize;
            fractional %= packetsPerSecond;
            urb->iso_frame_desc[i].length = length;
            urb->iso_frame_desc[i].actual_length = 0;
            urb->iso_frame_desc[i].status = 0;
            size += length;
        }
        urb->buffer_length = size;
        urb->status = 0; urb->error_count = 0;
        std::lock_guard<std::mutex> guard(lock);
        const size_t copied = paused ? 0 : std::min(count, static_cast<size_t>(size));
        const size_t first = std::min(copied, ring.size() - read);
        std::memcpy(transfer.bytes.data(), ring.data() + read, first);
        std::memcpy(transfer.bytes.data() + first, ring.data(), copied - first);
        read = (read + copied) % ring.size(); count -= copied;
        std::memset(transfer.bytes.data() + copied, 0, size - copied);
    }
    void loop() {
        std::vector<std::unique_ptr<Transfer>> transfers;
        for (int i = 0; i < depth; ++i) transfers.emplace_back(new Transfer(maximum * packets, packets));
        int pending = 0;
        for (auto& transfer : transfers) {
            fill(*transfer);
            if (ioctl(fd, USBDEVFS_SUBMITURB, transfer->urb) < 0) { error = errno; running = false; break; }
            transfer->pending = true; ++pending;
        }
        while (running) {
            pollfd watched{fd, POLLOUT, 0};
            const int polled = poll(&watched, 1, 20);
            if (polled < 0) { if (errno == EINTR) continue; error = errno; break; }
            if (polled == 0) continue;
            void* completed = nullptr;
            if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed) < 0) {
                if (errno == EAGAIN && !(watched.revents & (POLLERR | POLLHUP))) continue;
                error = errno; break;
            }
            auto* urb = static_cast<usbdevfs_urb*>(completed);
            auto* transfer = static_cast<Transfer*>(urb->usercontext);
            transfer->pending = false; --pending;
            if (urb->status || urb->error_count) { error = urb->status ? -urb->status : EIO; break; }
            bool packetError = false;
            for (int i = 0; i < packets; ++i) if (urb->iso_frame_desc[i].status) packetError = true;
            if (packetError) { error = EIO; break; }
            fill(*transfer);
            if (ioctl(fd, USBDEVFS_SUBMITURB, urb) < 0) { error = errno; break; }
            transfer->pending = true; ++pending;
            // A submitted URB alone does not prove the controller completed a transfer.
            ready = true;
        }
        running = false;
        for (auto& transfer : transfers) if (transfer->pending) ioctl(fd, USBDEVFS_DISCARDURB, transfer->urb);
        // Reap cancellations before freeing buffers still owned by the kernel.
        while (pending > 0) {
            void* completed = nullptr;
            if (ioctl(fd, USBDEVFS_REAPURB, &completed) < 0) {
                if (errno == EINTR) continue;
                break; // ENODEV: the disconnected usbfs device has no remaining requests.
            }
            --pending;
        }
    }
};
std::mutex registryLock;
std::unordered_map<jlong, std::shared_ptr<Stream>> registry;
jlong nextHandle = 1;
std::shared_ptr<Stream> find(jlong handle) {
    std::lock_guard<std::mutex> guard(registryLock);
    auto found = registry.find(handle);
    return found == registry.end() ? nullptr : found->second;
}
}
extern "C" JNIEXPORT jint JNICALL Java_com_lladlam_melox_usb_UsbAudioNative_speed(JNIEnv*, jobject, jint fd) {
    const int result = ioctl(fd, USBDEVFS_GET_SPEED, nullptr);
    return result < 0 ? -errno : result;
}
extern "C" JNIEXPORT void JNICALL Java_com_lladlam_melox_usb_UsbAudioNative_restore(JNIEnv*, jobject, jint fd, jint interfaceNumber) {
    usbdevfs_ioctl request{interfaceNumber, USBDEVFS_CONNECT, nullptr};
    ioctl(fd, USBDEVFS_IOCTL, &request);
}
extern "C" JNIEXPORT jlong JNICALL Java_com_lladlam_melox_usb_UsbAudioNative_open(JNIEnv*, jobject, jint fd, jint ep, jint rate, jint max, jint period, jint frame) {
    if (rate <= 0 || rate > 192000 || max <= 0 || max > 1024 ||
        (period != 1000 && period != 2000 && period != 4000 && period != 8000) ||
        (frame != 4 && frame != 6 && frame != 8) || ((rate + period - 1) / period) * frame > max) return 0;
    auto stream = std::make_shared<Stream>(fd, ep, rate, max, period, frame);
    if (stream->fd < 0) return 0;
    std::lock_guard<std::mutex> guard(registryLock);
    const jlong handle = nextHandle++;
    registry.emplace(handle, stream);
    stream->thread = std::thread([stream] { stream->loop(); });
    return handle;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_lladlam_melox_usb_UsbAudioNative_offer(JNIEnv* env, jobject, jlong handle, jbyteArray bytes) {
    auto stream = find(handle);
    if (!stream || !stream->running) return false;
    const auto size = static_cast<size_t>(env->GetArrayLength(bytes));
    if (size % stream->frameSize) { stream->error = EINVAL; return false; }
    if (size == 0) return true;
    std::vector<unsigned char> copy(size);
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(copy.data()));
    if (env->ExceptionCheck()) return false;
    std::lock_guard<std::mutex> guard(stream->lock);
    if (size > stream->ring.size() - stream->count) { stream->error = ENOBUFS; return false; }
    size_t write = (stream->read + stream->count) % stream->ring.size();
    const size_t first = std::min(size, stream->ring.size() - write);
    std::memcpy(stream->ring.data() + write, copy.data(), first);
    std::memcpy(stream->ring.data(), copy.data() + first, size - first);
    stream->count += size;
    return true;
}
extern "C" JNIEXPORT void JNICALL Java_com_lladlam_melox_usb_UsbAudioNative_setPaused(JNIEnv*, jobject, jlong handle, jboolean paused) {
    auto stream = find(handle);
    if (!stream) return;
    std::lock_guard<std::mutex> guard(stream->lock);
    stream->paused = paused;
}
extern "C" JNIEXPORT void JNICALL Java_com_lladlam_melox_usb_UsbAudioNative_flush(JNIEnv*, jobject, jlong handle) {
    auto stream = find(handle);
    if (!stream) return;
    std::lock_guard<std::mutex> guard(stream->lock);
    stream->read = 0;
    stream->count = 0;
}
extern "C" JNIEXPORT jint JNICALL Java_com_lladlam_melox_usb_UsbAudioNative_failure(JNIEnv*, jobject, jlong handle) {
    auto stream = find(handle);
    return stream ? stream->error.load() : ENODEV;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_lladlam_melox_usb_UsbAudioNative_ready(JNIEnv*, jobject, jlong handle) {
    auto stream = find(handle);
    return stream && stream->ready && stream->running;
}
extern "C" JNIEXPORT void JNICALL Java_com_lladlam_melox_usb_UsbAudioNative_stop(JNIEnv*, jobject, jlong handle) {
    std::shared_ptr<Stream> stream;
    {
        std::lock_guard<std::mutex> guard(registryLock);
        auto found = registry.find(handle);
        if (found == registry.end()) return;
        stream = found->second; registry.erase(found);
    }
    stream->shutdown();
}
