package com.lladlam.melox.core.provider.lxuser

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.security.NetworkSecurityPolicy
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.lladlam.melox.core.network.MeloXHttpClient
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Run with -PmeloxTestInstrumentationRunner=...LxRuntimeDeviceRunner. */
class LxRuntimeDeviceRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            runRegression()
            runRestrictedHttpRegression()
            result.putString("result", "QuickJS, isolated HTTP and media range regression passed")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("error", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun runRegression() {
        val script = """
            globalThis.lx.on(globalThis.lx.EVENT_NAMES.request, ({info}) =>
              new Promise((resolve, reject) => {
                globalThis.lx.request(info.musicInfo.url, {method:'GET', timeout:5000}, (err, response, body) => {
                  if (err) reject(err); else resolve(body.url + '?id=' + info.musicInfo.id)
                })
              }))
            globalThis.lx.send(globalThis.lx.EVENT_NAMES.inited, {
              sources: {tx: {qualitys:['128k'], actions:['musicUrl']}}
            })
        """.trimIndent()
        val id = "device-thread-regression"
        val clients = Executors.newFixedThreadPool(2)
        val requestsSeen = AtomicInteger()
        val fixture = OkHttpClient.Builder().addInterceptor { chain ->
            // Regression: previous drain stopped after about two seconds.
            if (requestsSeen.incrementAndGet() == 1) Thread.sleep(2_300L)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"url":"https://example.invalid/audio"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val runtime = LxUserRuntime(fixture)
        try {
            runtime.load(LxUserScript(script))
            val requests = (1..2).map { index ->
                clients.submit<Any?> {
                    check(runtime.supportsSource("tx"))
                    runtime.callAction("musicUrl", mapOf(
                        "source" to "tx", "type" to "128k",
                        "musicInfo" to mapOf("id" to index, "url" to "https://example.invalid/api"),
                    ))
                }
            }
            requests.forEachIndexed { index, request ->
                check(request.get(15, TimeUnit.SECONDS) == "https://example.invalid/audio?id=${index + 1}")
            }
            check(requestsSeen.get() == 2)
            runtime.close()
            check(runCatching { runtime.callAction("musicUrl", emptyMap()) }.isFailure)

            val cached = LxUserRuntime.session(id, script)
            check(LxUserRuntime.session(id, script) === cached)
            LxUserRuntime.evict(id)
            val replacement = LxUserRuntime.session(id, script)
            check(replacement !== cached && replacement.supportsSource("tx"))
        } finally {
            runtime.close()
            LxUserRuntime.evict(id)
            clients.shutdownNow()
        }
    }

    @OptIn(UnstableApi::class)
    private fun runRestrictedHttpRegression() {
        val policy = NetworkSecurityPolicy.getInstance()
        check(!policy.isCleartextTrafficPermitted("unrelated.example"))
        check(policy.isCleartextTrafficPermitted("fixture.media-http.melox.invalid"))

        val failure = AtomicReference<Throwable?>()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val fixture = Thread {
                try {
                    repeat(2) { index ->
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val input = socket.getInputStream().bufferedReader()
                            val line = checkNotNull(input.readLine())
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val header = checkNotNull(input.readLine())
                                if (header.isEmpty()) break
                                headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                            }
                            check(headers["host"] == "127.0.0.1:${server.localPort}")
                            val body: String
                            val status: String
                            val contentType: String
                            val extra: String
                            if (index == 0) {
                                check(line.startsWith("GET /api "))
                                body = """{"url":"https://example.invalid/audio"}"""
                                status = "200 OK"
                                contentType = "application/json"
                                extra = ""
                            } else {
                                check(line.startsWith("GET /audio "))
                                check(headers["range"] == "bytes=2-4")
                                body = "234"
                                status = "206 Partial Content"
                                contentType = "application/octet-stream"
                                extra = "Content-Range: bytes 2-4/10\r\n"
                            }
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            val response = "HTTP/1.1 $status\r\nContent-Type: $contentType\r\n" +
                                "${extra}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            socket.getOutputStream().apply {
                                write(response.toByteArray(Charsets.US_ASCII))
                                write(bytes)
                                flush()
                            }
                        }
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }.apply { isDaemon = true; start() }
            val base = "http://127.0.0.1:${server.localPort}"
            val script = """
                lx.on(lx.EVENT_NAMES.request, ({info}) => new Promise((resolve, reject) => {
                  lx.request(info.musicInfo.url, {}, (err, response, body) => {
                    if (err) reject(err); else resolve(body.url)
                  })
                }))
                lx.send(lx.EVENT_NAMES.inited, {sources: {tx: {qualitys:['128k'], actions:['musicUrl']}}})
            """.trimIndent()
            LxUserRuntime().use { runtime ->
                runtime.load(LxUserScript(script))
                check(runtime.callAction("musicUrl", mapOf(
                    "source" to "tx", "type" to "128k", "musicInfo" to mapOf("url" to "$base/api"),
                )) == "https://example.invalid/audio")
            }
            val source = OkHttpDataSource.Factory(MeloXHttpClient.thirdPartyAndMedia).createDataSource()
            try {
                val spec = DataSpec.Builder().setUri(Uri.parse("$base/audio")).setPosition(2).setLength(3).build()
                check(source.open(spec) == 3L)
                val buffer = ByteArray(3)
                var read = 0
                while (read < buffer.size) {
                    val count = source.read(buffer, read, buffer.size - read)
                    check(count != C.RESULT_END_OF_INPUT && count > 0)
                    read += count
                }
                check(buffer.toString(Charsets.UTF_8) == "234")
                check(source.uri.toString() == "$base/audio")
            } finally {
                source.close()
            }
            fixture.join(5_000)
            check(!fixture.isAlive) { "HTTP fixture did not finish" }
            failure.get()?.let { throw it }
        }
    }
}
