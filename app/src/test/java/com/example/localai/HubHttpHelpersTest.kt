package com.example.localai

import com.example.localai.data.hf.NextLink
import com.example.localai.data.hf.parseNextLink
import com.example.localai.data.hf.retryAfterSeconds
import java.time.ZoneOffset
import java.time.ZonedDateTime
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubHttpHelpersTest {

    private val origin = "https://huggingface.co/".toHttpUrl()

    @Test
    fun retryAfter_parsesSecondsAndClampsNegative() {
        assertEquals(7L, retryAfterSeconds(Headers.headersOf("Retry-After", "7")))
        assertEquals(0L, retryAfterSeconds(Headers.headersOf("Retry-After", "-5")))
    }

    @Test
    fun retryAfter_parsesHttpDate() {
        val now = ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, ZoneOffset.UTC)
        val headers = Headers.headersOf("Retry-After", "Wed, 14 Oct 2026 12:00:30 GMT")
        assertEquals(30L, retryAfterSeconds(headers, now))
    }

    @Test
    fun retryAfter_fallsBackToHubRateLimitHeader() {
        val headers = Headers.headersOf("RateLimit", "\"api\";r=0;t=126")
        assertEquals(126L, retryAfterSeconds(headers))
    }

    @Test
    fun retryAfter_unknownWhenNoUsableHeader() {
        assertNull(retryAfterSeconds(Headers.headersOf("Retry-After", "soon")))
        assertNull(retryAfterSeconds(Headers.headersOf()))
    }

    @Test
    fun nextLink_readsHubLinkHeader() {
        val link = "<https://huggingface.co/api/models?limit=2&cursor=abc>; rel=\"next\""
        val next = parseNextLink(Headers.headersOf("Link", link), origin)
        assertEquals("abc", ((next as NextLink.Url).url).queryParameter("cursor"))
    }

    @Test
    fun nextLink_ignoresOtherRelsAndPicksNextAmongSeveral() {
        val link = "<https://huggingface.co/a>; rel=\"prev\", <https://huggingface.co/b?cursor=z>; rel=\"next\""
        val next = parseNextLink(Headers.headersOf("Link", link), origin)
        assertEquals("z", (next as NextLink.Url).url.queryParameter("cursor"))
        assertEquals(NextLink.None, parseNextLink(Headers.headersOf("Link", "<https://huggingface.co/a>; rel=\"prev\""), origin))
        assertEquals(NextLink.None, parseNextLink(Headers.headersOf(), origin))
    }

    @Test
    fun nextLink_resolvesRelativeAndRejectsForeignHosts() {
        val relative = parseNextLink(Headers.headersOf("Link", "</api/models?cursor=q>; rel=\"next\""), origin)
        assertEquals("https://huggingface.co/api/models?cursor=q", (relative as NextLink.Url).url.toString())
        val foreign = parseNextLink(Headers.headersOf("Link", "<https://evil.example/x>; rel=\"next\""), origin)
        assertTrue(foreign is NextLink.Foreign)
        val downgraded = parseNextLink(Headers.headersOf("Link", "<http://huggingface.co/x>; rel=\"next\""), origin)
        assertTrue(downgraded is NextLink.Foreign)
    }
}
