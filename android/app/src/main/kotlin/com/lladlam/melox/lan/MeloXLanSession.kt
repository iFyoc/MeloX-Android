package com.lladlam.melox.lan

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal enum class MeloXLanOutputMode { Local, Cast, Together }

/** A control connection belongs to the process, never to a song, page, or dialog. */
internal object MeloXLanSession {
    val connections = mutableStateListOf<MeloXLanClient>()
    var client by mutableStateOf<MeloXLanClient?>(null)
        private set
    var device by mutableStateOf<MeloXLanDevice?>(null)
        private set
    var mode by mutableStateOf(MeloXLanOutputMode.Local)
        private set
    var busy by mutableStateOf(false)
        private set
    private val operations = Mutex()
    @Volatile private var restartingCast = false

    suspend fun connect(context: Context, target: MeloXLanDevice, pin: String?): MeloXLanConnectResult =
        operations.withLock {
            val existing = connections.firstOrNull { it.device.deviceId == target.deviceId && it.isConnected }
            if (existing != null) {
                if (client !== existing && mode == MeloXLanOutputMode.Cast) client?.let { endOutput(it) }
                client = existing
                device = target
                mode = if (MeloXLanTogetherCoordinator.contains(existing)) MeloXLanOutputMode.Together
                    else if (MeloXLanRuntime.activeSessionClient() === existing && MeloXLanRuntime.isCasting()) MeloXLanOutputMode.Cast
                    else MeloXLanOutputMode.Local
                return@withLock MeloXLanConnectResult.Connected
            }
            busy = true
            try {
                lateinit var created: MeloXLanClient
                created = MeloXLanRuntime.newClient(
                    context.applicationContext, target,
                    onEvent = { event ->
                        MeloXLanRuntime.onPeerEvent(created, event)
                        if (client === created && mode == MeloXLanOutputMode.Cast && !restartingCast &&
                            event.optString("event") == "cast.stopped") {
                            MeloXLanRuntime.stopCastSender()
                            mode = MeloXLanOutputMode.Local
                        }
                        if (client === created && event.optString("event") == "together.stopped") {
                            MeloXLanTogetherCoordinator.remove(created)
                            mode = MeloXLanOutputMode.Local
                        }
                    },
                    onRequest = { request ->
                        if (request.optString("action") == "control") {
                            JSONObject().put("ok", MeloXLanRuntime.hostControl(
                                request.optString("command"),
                                request.optLong("positionMs", -1L).takeIf { it >= 0L },
                            ))
                        } else null
                    },
                    onDisconnected = {
                        MeloXLanTogetherCoordinator.remove(created)
                        connections.remove(created)
                        if (client === created) {
                            client = null
                            device = null
                            mode = MeloXLanOutputMode.Local
                            if (MeloXLanRuntime.activeSessionClient() === created) MeloXLanRuntime.stopCastSender()
                        }
                    },
                )
                val result = created.connect(pin)
                if (result == MeloXLanConnectResult.Connected) {
                    if (mode == MeloXLanOutputMode.Cast) client?.let { endOutput(it) }
                    connections.add(created)
                    client = created
                    device = target
                    mode = MeloXLanOutputMode.Local
                } else created.close()
                result
            } finally { busy = false }
        }

    suspend fun transfer(): Boolean = operations.withLock {
        com.lladlam.melox.usb.MeloXUsbAudioSession.stop()
        com.lladlam.melox.miplay.MiPlaySession.disconnect()
        com.lladlam.melox.dlna.DlnaSession.disconnect(resumeLocal = false)
        val connection = client ?: return@withLock false
        val track = MeloXLanRuntime.currentTrack() ?: return@withLock false
        busy = true
        try {
            endOutput(connection)
            connection.transfer(track, MeloXLanRuntime.queueSnapshot(), MeloXLanRuntime.snapshot()?.positionMs ?: 0L)
        } finally { busy = false }
    }

    suspend fun cast(context: Context): Boolean = operations.withLock {
        com.lladlam.melox.usb.MeloXUsbAudioSession.stop()
        com.lladlam.melox.miplay.MiPlaySession.disconnect()
        com.lladlam.melox.dlna.DlnaSession.disconnect(resumeLocal = false)
        val connection = client ?: return@withLock false
        val target = device ?: return@withLock false
        busy = true
        try {
            endOutput(connection)
            val handshake = connection.startCast() ?: return@withLock false
            val started = MeloXLanRuntime.startCastSender(context.applicationContext, target, handshake, connection)
            if (started) mode = MeloXLanOutputMode.Cast else connection.stopCast()
            started
        } finally { busy = false }
    }

    suspend fun together(context: Context, provideAudio: Boolean = true): Boolean = operations.withLock {
        com.lladlam.melox.usb.MeloXUsbAudioSession.stop()
        com.lladlam.melox.miplay.MiPlaySession.disconnect()
        com.lladlam.melox.dlna.DlnaSession.disconnect(resumeLocal = false)
        val connection = client ?: return@withLock false
        busy = true
        try {
            if (mode == MeloXLanOutputMode.Cast) endOutput(connection)
            if (!MeloXLanRuntime.startTogetherSession(context, connection, provideAudio)) return@withLock false
            mode = MeloXLanOutputMode.Together
            true
        } finally { busy = false }
    }

    suspend fun useLocal() = operations.withLock {
        busy = true
        try { client?.let { endOutput(it) } } finally { busy = false }
    }

    suspend fun resumeCastAfterFormatChange(context: Context, connection: MeloXLanClient) = operations.withLock {
        if (client !== connection || mode != MeloXLanOutputMode.Cast) return@withLock
        val target = device ?: return@withLock
        restartingCast = true
        try {
            connection.stopCast()
            val handshake = connection.startCast()
            mode = if (handshake != null && MeloXLanRuntime.startCastSender(context, target, handshake, connection)) {
                MeloXLanOutputMode.Cast
            } else {
                MeloXCastAudioTap.restoreLocalOutput()
                MeloXLanOutputMode.Local
            }
        } finally { restartingCast = false }
    }

    fun castStopped(connection: MeloXLanClient) {
        if (client === connection && mode == MeloXLanOutputMode.Cast) mode = MeloXLanOutputMode.Local
    }

    private suspend fun endOutput(connection: MeloXLanClient) {
        val previous = mode
        mode = MeloXLanOutputMode.Local
        if (previous == MeloXLanOutputMode.Cast) {
            // Await the old stop acknowledgement before starting a new receiver.
            MeloXLanRuntime.stopCastSender(notifyRemote = false)
            connection.stopCast()
        }
        if (previous == MeloXLanOutputMode.Together) {
            MeloXLanTogetherCoordinator.remove(connection)
            connection.stopTogether()
        }
    }

    fun disconnect() {
        client = null
        device = null
        mode = MeloXLanOutputMode.Local
        MeloXLanRuntime.stopCastSender()
        MeloXLanRuntime.stopHostTogether()
        connections.toList().forEach { it.close() }
        connections.clear()
    }
}
