package dev.undefinedteam.wearagent.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import dev.undefinedteam.wearagent.agent.InsecureTls.insecureTls
import javax.net.ssl.SSLHandshakeException

/**
 * A self-signed HTTPS server stands in for the certificate failures this setting exists for
 * (self-signed gateway, or a device clock that makes a valid certificate look unissued).
 */
class InsecureTlsTest {
    private fun selfSignedServer(): MockWebServer {
        val localhost = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .commonName("localhost")
            .build()
        val serverCerts = HandshakeCertificates.Builder()
            .heldCertificate(localhost)
            .build()
        return MockWebServer().apply {
            useHttps(serverCerts.sslSocketFactory(), false)
            start()
        }
    }

    @Test
    fun defaultClientRejectsSelfSignedCertificate() {
        val server = selfSignedServer()
        try {
            server.enqueue(MockResponse().setBody("ok"))
            val client = OkHttpClient.Builder().build()
            try {
                client.newCall(Request.Builder().url(server.url("/v1/models")).build()).execute().close()
                fail("self-signed certificate was accepted without opt-in")
            } catch (e: Exception) {
                // Any TLS failure is the point: the chain must not verify.
                assertTrue(
                    "expected a TLS failure, got $e",
                    e is SSLHandshakeException || e.cause is SSLHandshakeException ||
                            e.javaClass.simpleName.contains("SSL") ||
                            e.javaClass.simpleName.contains("Certificate"),
                )
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun insecureTlsAcceptsSelfSignedCertificate() {
        val server = selfSignedServer()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"model_a"}]}"""))
            val client = OkHttpClient.Builder().insecureTls(true).build()
            val body = client.newCall(Request.Builder().url(server.url("/v1/models")).build())
                .execute().use { it.body?.string().orEmpty() }
            assertTrue("expected payload, got $body", body.contains("model_a"))
        } finally {
            server.shutdown()
        }
    }

    /** The setting must reach the provider that actually opens the socket. */
    @Test
    fun providerHonoursEndpointFlag() {
        val server = selfSignedServer()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"model_a"}]}"""))
            val request = ModelRequest(server.url("/v1").toString(), "test-model", "key", insecureTls = true)
            val models = ChatProvider(EndpointKind.COMPLETIONS, insecureTls = true).models(request)
            assertEquals(listOf("model_a"), models)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun providerRejectsSelfSignedCertificateWithoutFlag() {
        val server = selfSignedServer()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"model_a"}]}"""))
            val request = ModelRequest(server.url("/v1").toString(), "test-model", "key")
            try {
                ChatProvider(EndpointKind.COMPLETIONS).models(request)
                fail("provider verified a self-signed certificate without the flag")
            } catch (e: Exception) {
                assertTrue("unexpected error: $e", e !is AssertionError)
            }
        } finally {
            server.shutdown()
        }
    }
}
