package com.lladlam.melox.core.network

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RestrictedHttpClientTest {
    @Test
    fun dynamicHostsFollowRedirectsAndProtectCredentials() {
        fixture(requests = 2) { port, index, _ ->
            when (index) {
                0 -> response("302 Found", "Location: http://second.test:$port/audio?sig=a%2Fb\r\n")
                else -> response("206 Partial Content", "Content-Range: bytes 2-4/8\r\n", "cde")
            }
        }.use { server ->
            val client = fixtureClient()
            val request = Request.Builder().url("http://first.test:${server.port}/start?q=a%2Fb")
                .header("Authorization", "Bearer private")
                .header("Cookie", "sid=private")
                .header("X-Api-Key", "private-key")
                .header("Range", "bytes=2-4")
                .build()
            client.newCall(request).execute().use { result ->
                assertEquals(206, result.code)
                assertEquals("cde", result.body.string())
                assertEquals("http://second.test:${server.port}/audio?sig=a%2Fb", result.request.url.toString())
                assertFalse(result.request.url.host.endsWith(".invalid"))
            }
            server.assertCompleted()
            val first = server.received[0]
            val second = server.received[1]
            assertEquals("GET /start?q=a%2Fb HTTP/1.1", first.line)
            assertEquals("first.test:${server.port}", first.headers["host"])
            assertEquals("Bearer private", first.headers["authorization"])
            assertEquals("sid=private", first.headers["cookie"])
            assertEquals("private-key", first.headers["x-api-key"])
            assertEquals("GET /audio?sig=a%2Fb HTTP/1.1", second.line)
            assertEquals("second.test:${server.port}", second.headers["host"])
            assertEquals("bytes=2-4", second.headers["range"])
            assertNull(second.headers["authorization"])
            assertNull(second.headers["cookie"])
            assertNull(second.headers["x-api-key"])
        }
    }

    @Test
    fun relativeRedirectOnSameOriginKeepsSourceHeaders() {
        fixture(requests = 2) { _, index, _ ->
            if (index == 0) {
                response("302 Found", "Location: /final?ok=1\r\n")
            } else {
                response("200 OK", body = "success")
            }
        }.use { server ->
            val request = Request.Builder().url("http://first.test:${server.port}/start")
                .header("Host", "custom-host.test")
                .header("X-Api-Key", "source-key")
                .build()
            fixtureClient().newCall(request).execute().use { result ->
                assertEquals("success", result.body.string())
                assertEquals("http://first.test:${server.port}/final?ok=1", result.request.url.toString())
            }
            server.assertCompleted()
            server.received.forEach { request ->
                assertEquals("custom-host.test", request.headers["host"])
                assertEquals("source-key", request.headers["x-api-key"])
            }
            assertEquals("GET /final?ok=1 HTTP/1.1", server.received[1].line)
        }
    }

    @Test
    fun httpsIsNotDowngradedToPlaintext() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val task = executor.submit {
                    server.accept().use { socket ->
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            flush()
                        }
                    }
                }
                val request = Request.Builder().url("https://first.test:${server.localPort}/audio").build()
                val result = runCatching { fixtureClient().newCall(request).execute().close() }
                assertFalse("HTTPS must reject a plaintext server", result.isSuccess)
                task.get(10, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun doesNotInheritTheBaseClientsCookieJar() {
        fixture(requests = 1) { _, _, _ -> response("200 OK", body = "ok") }.use { server ->
            val cookieJar = object : CookieJar {
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    listOf(Cookie.Builder().name("session").value("private").domain("first.test").build())

                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            }
            val base = OkHttpClient.Builder().dns(fixtureDns()).cookieJar(cookieJar).build()
            val request = Request.Builder().url("http://first.test:${server.port}/").build()
            RestrictedHttpClient.create(base).newCall(request).execute().use { response ->
                assertEquals("ok", response.body.string())
            }
            server.assertCompleted()
            assertNull(server.received.single().headers["cookie"])
        }
    }

    private fun fixtureClient(): OkHttpClient = RestrictedHttpClient.create(
        OkHttpClient.Builder()
            .dns(fixtureDns())
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
    )

    private fun fixtureDns(): Dns = Dns { host ->
        check(host == "first.test" || host == "second.test") { "unexpected DNS host: $host" }
        listOf(InetAddress.getByName("127.0.0.1"))
    }

    private data class ReceivedRequest(val line: String, val headers: Map<String, String>)

    private class Fixture(
        requests: Int,
        respond: (Int, Int, ReceivedRequest) -> String,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val port: Int = server.localPort
        val received = CopyOnWriteArrayList<ReceivedRequest>()
        private val task = executor.submit {
            server.soTimeout = 60_000
            repeat(requests) { index ->
                server.accept().use { socket ->
                    socket.soTimeout = 30_000
                    val input = socket.getInputStream().bufferedReader()
                    val line = checkNotNull(input.readLine())
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = checkNotNull(input.readLine())
                        if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    val request = ReceivedRequest(line, headers)
                    received.add(request)
                    socket.getOutputStream().apply {
                        write(respond(port, index, request).toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
            }
        }

        fun assertCompleted() { task.get(60, TimeUnit.SECONDS) }

        override fun close() {
            server.close()
            executor.shutdownNow()
        }
    }

    private fun fixture(requests: Int, respond: (Int, Int, ReceivedRequest) -> String): Fixture =
        Fixture(requests, respond)

    private fun response(status: String, headers: String = "", body: String = ""): String =
        "HTTP/1.1 $status\r\n${headers}Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n$body"
}
