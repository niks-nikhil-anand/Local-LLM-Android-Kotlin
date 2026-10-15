package com.example.localai.chat

import com.example.localai.data.websearch.WebResult
import com.example.localai.data.websearch.WebSearchException
import com.example.localai.data.websearch.WikipediaSearchProvider
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class WikipediaSearchProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.close()

    private val provider by lazy { WikipediaSearchProvider(OkHttpClient(), baseUrl = server.url("/")) }

    private fun respond(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun search(q: String) = runBlocking { provider.search(q) }

    private val sample = """
        {"batchcomplete":true,"query":{"pages":[
          {"pageid":2,"title":"Kotlin","index":2,"extract":"Kotlin is a\nlanguage.","fullurl":"https://en.wikipedia.org/wiki/Kotlin"},
          {"pageid":1,"title":"Coroutine","index":1,"extract":"Coroutines are components.","fullurl":"https://en.wikipedia.org/wiki/Coroutine"},
          {"pageid":3,"title":"Phish","index":3,"extract":"x","fullurl":"https://evil.example.com/wiki/Phish"},
          {"pageid":4,"title":"Plain","index":4,"extract":"x","fullurl":"http://en.wikipedia.org/wiki/Plain"}
        ]}}
    """.trimIndent()

    @Test
    fun onlyTheQueryIsSentWithNoCookiesOrCredentials() {
        respond(sample)
        search("kotlin coroutines")
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/w/api.php", request.url.encodedPath)
        assertEquals("kotlin coroutines", request.url.queryParameter("gsrsearch"))
        assertEquals("3", request.url.queryParameter("gsrlimit"))
        assertNull(request.headers["Cookie"])
        assertNull(request.headers["Authorization"])
        assertEquals(0L, request.bodySize)
        // Every parameter is a fixed API option except the query itself.
        val fixed = setOf(
            "action", "format", "formatversion", "generator", "gsrlimit", "prop", "exintro", "explaintext",
            "exchars", "exlimit", "inprop", "redirects"
        )
        assertEquals(fixed + "gsrsearch", request.url.queryParameterNames)
    }

    @Test
    fun resultsAreOrderedAndOnlyHttpsWikipediaLinksAreKept() {
        respond(sample)
        assertEquals(
            listOf(
                WebResult("Coroutine", "https://en.wikipedia.org/wiki/Coroutine", "Coroutines are components."),
                WebResult("Kotlin", "https://en.wikipedia.org/wiki/Kotlin", "Kotlin is a language.")
            ),
            search("kotlin")
        )
    }

    @Test
    fun fieldsAreBounded() {
        respond("""{"query":{"pages":[{"title":"${"t".repeat(500)}","index":1,"extract":"${"e".repeat(5000)}","fullurl":"https://en.wikipedia.org/wiki/T"}]}}""")
        val r = search("t").single()
        assertEquals(120, r.title.length)
        assertEquals(600, r.snippet.length)
    }

    @Test
    fun noMatchesIsAnEmptyListNotAnError() {
        respond("""{"batchcomplete":true}""")
        assertTrue(search("zzqqxx").isEmpty())
    }

    @Test
    fun anEmptyQueryIsNotSent() {
        assertTrue(search("   ").isEmpty())
        assertEquals(0, server.requestCount)
    }

    private fun assertFails(expected: String) {
        try {
            search("q")
            fail("expected a WebSearchException")
        } catch (e: WebSearchException) {
            assertTrue(e.message, e.message!!.contains(expected))
        }
    }

    @Test
    fun httpErrorsMalformedAndOversizedResponsesFailClearly() {
        respond("busy", code = 503)
        assertFails("HTTP 503")
        respond("<html>not json</html>")
        assertFails("could not be read")
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(300 * 1024) { 'a'.code.toByte() })).build())
        assertFails("unexpectedly large")
    }

    @Test
    fun anUnreachableServerIsANetworkError() {
        val dead = WikipediaSearchProvider(OkHttpClient(), baseUrl = server.url("/"))
        server.close()
        try {
            runBlocking { dead.search("q") }
            fail("expected a WebSearchException")
        } catch (e: WebSearchException) {
            assertTrue(e.message, e.message!!.contains("network error") || e.message!!.contains("no internet"))
        }
    }
}
