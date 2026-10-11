package io.github.deedg.cineswipe.data.homelab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomelabUrlsTest {

    private fun parse(raw: String) = HomelabUrls.parse(raw)

    @Test
    fun addressWithoutSchemeGetsHttpAndKeepsItsPort() {
        assertEquals("http://100.121.0.1:7878/", parse("100.121.0.1:7878").toString())
        assertEquals(8096, parse("nas.local:8096")?.port)
        assertEquals(5055, parse("http://192.168.1.20:5055/")?.port)
    }

    @Test
    fun basePathOfAReverseProxyIsKept() {
        assertEquals("https://films.example.org/jellyfin", parse(" https://films.example.org/jellyfin/ ").toString())
    }

    @Test
    fun invalidAddressesAreRejected() {
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("nas local:8096"))
        assertNull(parse("ftp://nas:21"))
    }

    @Test
    fun localAndTailscaleHostsArePrivate() {
        listOf("100.121.133.7", "192.168.1.2", "10.0.0.3", "172.20.1.1", "127.0.0.1", "nas", "nas.local", "box.tailnet-abc.ts.net", "fd7a:115c:a1e0::1")
            .forEach { assertTrue(it, HomelabUrls.isPrivateHost(it)) }
        listOf("8.8.8.8", "films.example.org", "172.32.0.1", "100.128.0.1", "2001:db8::1")
            .forEach { assertFalse(it, HomelabUrls.isPrivateHost(it)) }
    }

    @Test
    fun onlyPublicHttpIsInsecure() {
        assertTrue(HomelabUrls.isInsecurePublic(parse("http://films.example.org")!!))
        assertFalse(HomelabUrls.isInsecurePublic(parse("https://films.example.org")!!))
        assertFalse(HomelabUrls.isInsecurePublic(parse("100.64.1.2:8096")!!))
    }

    @Test
    fun extraHeadersAreReadLineByLine() {
        val headers = HomelabUrls.parseHeaders("CF-Access-Client-Id: abc\nligne sans séparateur\nX-Token:  xyz  \n: sans nom\nMauvais Nom: 1")
        assertEquals(mapOf("CF-Access-Client-Id" to "abc", "X-Token" to "xyz"), headers)
    }

    @Test
    fun jellyfinLinkAddsTheMissingScheme() {
        assertEquals("http://100.64.1.2:8096/web/#/details?id=abc", JellyfinClient.webUrl("100.64.1.2:8096", "abc"))
        assertEquals("https://films.example.org/jellyfin/web/#/details?id=abc", JellyfinClient.webUrl("https://films.example.org/jellyfin/", "abc"))
        assertEquals(null, JellyfinClient.webUrl("pas une adresse", "abc"))
    }
}
