package dev.undefinedteam.wearagent.utils

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Opt-in TLS bypass for a self-signed endpoint or a device clock that makes a valid certificate
 * look unissued. Accepts any certificate and hostname, so it also removes interception protection:
 * keep it an explicit per-endpoint choice, never on by default, and never shared with a verifying
 * client.
 */
object InsecureTls {
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val socketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    }.socketFactory

    /** Disables certificate and hostname verification when [enabled]. */
    fun OkHttpClient.Builder.insecureTls(enabled: Boolean): OkHttpClient.Builder = apply {
        if (enabled) {
            sslSocketFactory(socketFactory, trustAll)
            hostnameVerifier { _, _ -> true }
        }
    }
}