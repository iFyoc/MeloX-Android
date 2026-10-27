package com.lladlam.melox.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.lladlam.melox.lan.MeloXCastAudioTap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Experimental UAC1 output. Owns the USB interface; the native worker submits ISO usbfs URBs. */
internal object MeloXUsbAudioSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    var devices by mutableStateOf<List<UsbDevice>>(emptyList())
        private set
    var device by mutableStateOf<UsbDevice?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var volume by mutableStateOf(1f)
        private set
    var description by mutableStateOf("")
        private set
    val active: Boolean get() = device != null
    private var app: Context? = null
    private var permissionAction = ""
    private var permissionTarget: Int? = null
    private var permission: CompletableDeferred<Boolean>? = null
    private var connection: UsbDeviceConnection? = null
    private var face: UsbInterface? = null
    @Volatile private var handle = 0L
    @Volatile private var sampleRate = 0
    @Volatile private var bytesPerSample = 2
    @Volatile private var gain = 1f
    @Volatile private var feedFailed = false
    @Volatile private var playing = false
    private var monitor: Job? = null
    private var starting: Job? = null
    private var startingDevice: Int? = null
    private val pcmListener: (ByteArray) -> Unit = { bytes ->
        val current = handle
        if (current != 0L) {
            if (bytes.size % 4 != 0 || MeloXCastAudioTap.format.sampleRate != sampleRate || MeloXCastAudioTap.inputChannels != 2 ||
                MeloXCastAudioTap.inputEncoding != androidx.media3.common.C.ENCODING_PCM_16BIT) feedFailed = true
            else if (!UsbAudioNative.offer(current, UsbPcm.pack(bytes, gain, bytesPerSample))) feedFailed = true
        }
    }
    private fun manager(context: Context) = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION") val target = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                permissionAction -> if (target?.deviceId == permissionTarget) {
                    // Verify with UsbManager as well, rather than trusting an exported broadcast's extra.
                    permission?.complete(target != null && manager(context).hasPermission(target))
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDevices(context)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (target?.deviceId == permissionTarget) permission?.complete(false)
                    if (target != null && (target.deviceId == device?.deviceId || target.deviceId == startingDevice)) scope.launch {
                        stop(); error = "USB 设备已拔出，已恢复本机输出"
                        UsbAudioDiagnostics.result(error.orEmpty())
                    }
                    refreshDevices(context)
                }
            }
        }
    }
    fun refreshDevices(context: Context) {
        if (app == null) {
            app = context.applicationContext
            permissionAction = "${context.packageName}.USB_AUDIO_PERMISSION"
            val filter = IntentFilter(permissionAction).apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(checkNotNull(app), receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
        devices = manager(context).deviceList.values.filter { target ->
            (0 until target.interfaceCount).any { index ->
                target.getInterface(index).let { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 2 }
            }
        }
    }
    suspend fun start(context: Context, target: UsbDevice): Boolean = withContext(Dispatchers.Main) {
        operations.withLock {
            if (device?.deviceId == target.deviceId) return@withLock true
            busy = true; error = null
            starting = kotlin.coroutines.coroutineContext[Job]
            startingDevice = target.deviceId
            UsbAudioDiagnostics.begin(target.vendorId, target.productId)
            var opened: UsbDeviceConnection? = null
            var claimed: UsbInterface? = null
            var native = 0L
            try {
                refreshDevices(context)
                val usb = manager(context)
                if (!usb.hasPermission(target)) {
                    val result = CompletableDeferred<Boolean>()
                    permissionTarget = target.deviceId; permission = result
                    val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    val request = PendingIntent.getBroadcast(context, target.deviceId,
                        Intent(permissionAction).setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT or mutable)
                    try {
                        usb.requestPermission(target, request)
                        check(withTimeoutOrNull(60_000) { result.await() } == true && usb.hasPermission(target)) { "未获得 USB 权限或等待授权超时" }
                    } finally { permission = null; permissionTarget = null; request.cancel() }
                }
                closeStream()
                withContext(Dispatchers.IO) {
                    opened = usb.openDevice(target) ?: error("无法打开 USB 设备")
                    val conn = checkNotNull(opened)
                    val speed = UsbDeviceSpeed.resolve(UsbAudioNative.speed(conn.fileDescriptor), target.deviceName, target.vendorId, target.productId)
                    val rate = MeloXCastAudioTap.format.sampleRate
                    val raw = conn.rawDescriptors
                    UsbAudioDiagnostics.describe(speed, rate, MeloXCastAudioTap.inputChannels, MeloXCastAudioTap.inputEncoding, raw)
                    check(speed.speed != 0) { "无法确定 USB 连接速度（usbfs=${speed.ioctlResult}），请导出日志以便排查" }
                    check(speed.speed in 2..3) { "暂不支持此 USB 连接速度（${speed.speed}），请导出日志" }
                    check(MeloXCastAudioTap.inputChannels == 2 && MeloXCastAudioTap.inputEncoding == androidx.media3.common.C.ENCODING_PCM_16BIT) {
                        "当前解码输出不是双声道 PCM16，暂不能启用 USB 独占"
                    }
                    val selected = UsbAudioDescriptors.parse(raw).filter { it.canPlay(rate, speed.speed) }
                        .minByOrNull { it.bytesPerSample }
                        ?: error("未找到匹配的 UAC1 双声道自适应/同步输出（${rate}Hz）；UAC2 和异步反馈暂不支持，请导出日志")
                    val selectedFace = (0 until target.interfaceCount).map(target::getInterface).first {
                        it.id == selected.interfaceNumber && it.alternateSetting == selected.alternate
                    }
                    check(conn.claimInterface(selectedFace, true)) { "无法独占 USB 接口" }
                    claimed = selectedFace
                    check(conn.setInterface(selectedFace)) { "无法选择 USB 音频备用接口" }
                    if (selected.frequencyControl) {
                        val frequency = byteArrayOf(rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte())
                        check(conn.controlTransfer(0x22, 1, 0x0100, selected.endpoint, frequency, 3, 1_000) == 3) { "DAC 采样率设置失败" }
                    } else check((selected.rates.size == 1 && selected.rates.single() == rate && selected.rateRange == null) ||
                        (selected.rates.isEmpty() && selected.rateRange?.let { it.first == rate && it.last == rate } == true)) {
                        "DAC 无采样率控制，无法确认当前时钟"
                    }
                    native = UsbAudioNative.open(conn.fileDescriptor, selected.endpoint, rate, selected.maxPacketSize,
                        checkNotNull(selected.packetsPerSecond(speed.speed)), selected.bytesPerSample * 2)
                    check(native != 0L) { "USB 等时传输初始化失败" }
                    sampleRate = rate; bytesPerSample = selected.bytesPerSample
                    UsbAudioDiagnostics.result("Selected UAC1 interface=${selected.interfaceNumber} alt=${selected.alternate} " +
                        "endpoint=${selected.endpoint} interval=${selected.interval} max=${selected.maxPacketSize} " +
                        "PCM${selected.bitResolution}/${selected.bytesPerSample * 8}, rate=$rate")
                }
                check(withTimeoutOrNull(2_000) {
                    while (!UsbAudioNative.ready(native) && UsbAudioNative.failure(native) == 0) delay(10)
                    UsbAudioNative.ready(native) && UsbAudioNative.failure(native) == 0
                } == true) { "USB 等时请求未能启动（${UsbAudioNative.failure(native)}）" }
                com.lladlam.melox.dlna.DlnaSession.disconnect(resumeLocal = true)
                com.lladlam.melox.miplay.MiPlaySession.disconnect()
                com.lladlam.melox.lan.MeloXLanRuntime.stopCastSender()
                com.lladlam.melox.lan.MeloXLanRuntime.stopHostTogether()
                if (com.lladlam.melox.lan.MeloXLanRuntime.isGuestTogether()) com.lladlam.melox.lan.MeloXLanRuntime.stopGuestTogether()
                handle = native; native = 0
                connection = opened; opened = null
                face = claimed; claimed = null
                feedFailed = false
                UsbAudioNative.setPaused(handle, !playing)
                device = target; description = "${sampleRate}Hz · PCM16 → ${bytesPerSample * 8}位 · UAC1 独占（实验性）"
                MeloXCastAudioTap.attach(pcmListener)
                MeloXCastAudioTap.muteLocalOutput = true
                monitor = scope.launch {
                    while (active) {
                        delay(100)
                        val current = handle
                        val failure = if (current != 0L) UsbAudioNative.failure(current) else 0
                        if (feedFailed || failure != 0) {
                            monitor = null
                            stop(); error = "USB 输出中断或歌曲格式改变（$failure），已恢复本机输出"
                            UsbAudioDiagnostics.result(error.orEmpty())
                            break
                        }
                    }
                }
                Log.i("MeloXUsbAudio", "Exclusive interface started: $description")
                UsbAudioDiagnostics.result("started")
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = failure.message ?: "USB 独占启动失败"
                UsbAudioDiagnostics.result(error.orEmpty())
                Log.w("MeloXUsbAudio", "Exclusive output failed: $error", failure)
                false
            }
            catch (failure: LinkageError) {
                error = "USB 原生驱动未能加载：${failure.message.orEmpty()}"
                UsbAudioDiagnostics.result(error.orEmpty())
                false
            }
            finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    if (native != 0L) UsbAudioNative.stop(native)
                    claimed?.let { claimedFace -> opened?.let { releaseInterface(it, claimedFace, target) } }
                    opened?.close()
                }
                busy = false; starting = null; startingDevice = null
            }
        }
    }
    suspend fun stop() = withContext(Dispatchers.Main) {
        starting?.takeUnless { it == kotlin.coroutines.coroutineContext[Job] }?.cancel()
        operations.withLock { closeStream() }
    }
    private suspend fun closeStream() {
        // A monitor-triggered stop must not cancel its own cleanup midway.
        val previousMonitor = monitor; monitor = null
        if (previousMonitor != kotlin.coroutines.coroutineContext[Job]) previousMonitor?.cancel()
        val old = handle; handle = 0
        val wasActive = device != null
        if (wasActive) { MeloXCastAudioTap.detach(pcmListener); MeloXCastAudioTap.muteLocalOutput = false }
        val oldDevice = device
        device = null; description = ""
        val conn = connection; val claimed = face; connection = null; face = null
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            if (old != 0L) UsbAudioNative.stop(old)
            if (conn != null) {
                if (claimed != null) releaseInterface(conn, claimed, oldDevice)
                conn.close()
            }
        }
    }
    private fun releaseInterface(conn: UsbDeviceConnection, claimed: UsbInterface, target: UsbDevice?) {
        target?.let { device ->
            (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
                it.id == claimed.id && it.alternateSetting == 0
            }?.let(conn::setInterface)
        }
        conn.releaseInterface(claimed)
        UsbAudioNative.restore(conn.fileDescriptor, claimed.id)
    }
    fun changeVolume(value: Float) { volume = value.coerceIn(0f, 1f); gain = volume }
    fun onPlaybackChanged(isPlaying: Boolean) {
        playing = isPlaying
        handle.takeIf { it != 0L }?.let { UsbAudioNative.setPaused(it, !isPlaying) }
    }
    fun onSeek() { handle.takeIf { it != 0L }?.let(UsbAudioNative::flush) }
    fun release() { scope.launch { stop() } }
}
