package io.github.deedg.cineswipe.data.tmdb

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TmdbRateLimitTest {
    private val server = MockWebServer()
    private val waits = mutableListOf<Long>()
    private val client = OkHttpClient.Builder().addInterceptor(TmdbRateLimitInterceptor { waits += it }).build()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun get() = client.newCall(Request.Builder().url(server.url("/3/search/movie")).build()).execute()

    @Test
    fun waitsAndRetriesAfterTooManyRequests() {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "2").build())
        server.enqueue(MockResponse.Builder().code(429).build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"results":[]}""").build())
        get().use { response ->
            assertEquals(200, response.code)
            assertEquals("""{"results":[]}""", response.body.string())
        }
        // Le délai demandé par TMDB, puis l'attente qui double.
        assertEquals(listOf(2_000L, 2_000L), waits)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun givesUpAfterFourRetries() {
        repeat(6) { server.enqueue(MockResponse.Builder().code(429).build()) }
        get().use { assertEquals(429, it.code) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), waits)
        assertEquals(5, server.requestCount)
    }
}
