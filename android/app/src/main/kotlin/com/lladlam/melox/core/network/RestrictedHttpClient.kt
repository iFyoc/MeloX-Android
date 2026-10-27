package com.lladlam.melox.core.network

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * HTTP transport exclusively for installed source scripts and resolved media.
 *
 * Android's network policy applies to the whole app, not individual clients.
 * The policy therefore allows one reserved, non-public hostname. This client
 * maps each HTTP destination to a distinct subdomain of that hostname and
 * resolves it directly to the original host. The server receives the original
 * Host header; HTTPS requests continue to use normal TLS and host verification.
 * No account cookie jar or shared API cache is attached to this client.
 */
internal object RestrictedHttpClient {
    private const val ALIAS_DOMAIN = "media-http.melox.invalid"

    fun create(base: OkHttpClient): OkHttpClient {
        val routes = ConcurrentHashMap<String, String>()
        val upstreamDns = base.dns
        val upstreamProxySelector = base.proxySelector

        fun alias(url: HttpUrl): HttpUrl {
            if (url.scheme != "http") return url
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(url.host.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val host = "${digest.take(32)}.${digest.drop(32)}.$ALIAS_DOMAIN"
            routes[host] = url.host
            return url.newBuilder().host(host).build()
        }

        fun original(url: HttpUrl): HttpUrl {
            if (url.scheme != "http") return url
            val host = routes[url.host] ?: return url
            return url.newBuilder().host(host).build()
        }

        return base.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cache(null)
            // Synthetic HTTP names must be resolved locally. HTTPS continues to
            // use the system proxy selector and normal certificate checks.
            .proxy(null)
            .proxySelector(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> =
                    if (uri.host?.endsWith(".$ALIAS_DOMAIN") == true) listOf(Proxy.NO_PROXY)
                    else upstreamProxySelector.select(uri)

                override fun connectFailed(uri: URI, sa: SocketAddress, error: IOException) {
                    upstreamProxySelector.connectFailed(uri, sa, error)
                }
            })
            .dns(Dns { host ->
                val destination = routes[host]
                when {
                    destination != null -> upstreamDns.lookup(destination)
                    host == ALIAS_DOMAIN || host.endsWith(".$ALIAS_DOMAIN") -> throw UnknownHostException(host)
                    else -> upstreamDns.lookup(host)
                }
            })
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val origin = Origin(request.url.scheme, request.url.host, request.url.port, request.header("Host"))
                val routed = request.newBuilder()
                    .url(alias(request.url))
                    .tag(Origin::class.java, origin)
                    .build()
                val response = chain.proceed(routed)
                // Media3 and LX callbacks must see the actual final URL, not an
                // internal alias (e.g. for relative media playlist references).
                response.newBuilder()
                    .request(response.request.newBuilder().url(original(response.request.url)).build())
                    .build()
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val realUrl = original(request.url)
                val origin = request.tag(Origin::class.java)
                val sameOrigin = origin?.matches(realUrl) == true
                val hostHeader = origin?.customHost?.takeIf { sameOrigin } ?: realUrl.hostHeader()
                val builder = request.newBuilder().header("Host", hostHeader)
                if (origin != null && !sameOrigin) {
                    request.headers.names().filter(::isSensitiveHeader).forEach(builder::removeHeader)
                }
                val response = chain.proceed(builder.build())
                val location = response.header("Location")
                val destination = location?.let(realUrl::resolve)
                if (response.code in 300..399 && destination != null) {
                    response.newBuilder().header("Location", alias(destination).toString()).build()
                } else {
                    response
                }
            }
            .build()
    }

    private data class Origin(val scheme: String, val host: String, val port: Int, val customHost: String?) {
        fun matches(url: HttpUrl): Boolean = scheme == url.scheme && host == url.host && port == url.port
    }

    private fun HttpUrl.hostHeader(): String {
        val name = if (':' in host) "[$host]" else host
        val defaultPort = if (scheme == "https") 443 else 80
        return if (port == defaultPort) name else "$name:$port"
    }

    private fun isSensitiveHeader(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return listOf("auth", "cookie", "credential", "key", "password", "secret", "session", "signature", "token")
            .any(lower::contains)
    }
}
