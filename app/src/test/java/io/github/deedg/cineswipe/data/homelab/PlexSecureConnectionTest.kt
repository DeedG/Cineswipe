package io.github.deedg.cineswipe.data.homelab

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Serveur Plex en « Connexions sécurisées : requises » : il ne répond qu'en https, avec un
 * certificat « *.<empreinte>.plex.direct ». L'adresse saisie (IP + port, en http) échoue ; le
 * test de connexion retrouve l'adresse plex.direct de la même IP et s'y connecte avec une
 * vérification TLS complète.
 */
class PlexSecureConnectionTest {

    private val server = MockWebServer()
    private lateinit var http: HomelabHttp

    @Before
    fun setUp() {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            // Certificat de test « *.<empreinte>.plex.direct », dans les ressources des tests.
            checkNotNull(PlexSecureConnectionTest::class.java.classLoader?.getResourceAsStream(KEYSTORE)).use { load(it, PASSWORD) }
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, PASSWORD) }.keyManagers
        val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        server.useHttps(serverContext.socketFactory)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.headers["X-Plex-Token"] != TOKEN -> json(401, "{}")
                request.url.encodedPath == "/" -> json(200, """{"MediaContainer":{"friendlyName":"mon-serveur","version":"1.42.1"}}""")
                request.url.encodedPath == "/library/sections" ->
                    json(200, """{"MediaContainer":{"Directory":[{"key":"1","title":"Films","type":"movie"}]}}""")
                else -> json(404, "{}")
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)

        // Le téléphone fait confiance aux autorités publiques qui signent les certificats Plex ;
        // ici, au certificat de test.
        val trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        val trustManager = trustFactory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientContext.socketFactory, trustManager)
            .dns(PlexDirectDns)
            // Connexion directe même si la machine de test passe par un proxy.
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        http = HomelabHttp(client)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun plainHttpFailsThenThePlexDirectAddressOfTheSameIpWorks() = runBlocking {
        val plex = PlexClient(http, clientId = "appareil")
        val typed = "http://127.0.0.1:${server.port}".toHttpUrl()

        try {
            plex.identity(typed, emptyMap(), TOKEN)
            fail("Le http doit être refusé")
        } catch (error: HomelabException) {
            assertTrue(error.kind == HomelabException.Kind.UNREACHABLE || error.kind == HomelabException.Kind.TLS)
        }

        val secure = plex.secureUrl(typed)
        assertEquals("https://127-0-0-1.$HASH.plex.direct:${server.port}/", secure.toString())

        val identity = plex.identity(secure!!, emptyMap(), TOKEN)
        assertEquals("mon-serveur", identity.friendlyName)
        assertEquals(listOf("Films"), plex.sections(secure, emptyMap(), TOKEN).map { it.title })
    }

    @Test
    fun theCertificateNamesAreReadWithoutTrustingTheIpAddress() = runBlocking {
        val names = http.certificateNames("http://127.0.0.1:${server.port}".toHttpUrl())
        assertEquals(listOf("*.$HASH.plex.direct"), names)
    }

    @Test
    fun anAddressThatIsNotAnIpIsLeftAlone() = runBlocking {
        val plex = PlexClient(http, clientId = "appareil")
        assertEquals(null, plex.secureUrl("http://localhost:${server.port}".toHttpUrl()))
    }

    private fun json(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private companion object {
        const val KEYSTORE = "plex-direct-test.p12"
        val PASSWORD = "cineswipe".toCharArray()
        const val HASH = "0123456789abcdef0123456789abcdef"
        const val TOKEN = "jeton-plex"
    }
}
