package app.vidro.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlSnifferTest {
    @Test
    fun progressiveExtensionsAndQuery() {
        val mp4 = UrlSniffer.sniff("https://cdn.example.com/v/movie.mp4?token=abc")
        assertEquals(UrlSniffer.Kind.PROGRESSIVE, mp4?.kind)
        assertEquals(UrlSniffer.Kind.PROGRESSIVE, UrlSniffer.sniff("https://cdn.example.com/a.webm")?.kind)
        assertEquals(UrlSniffer.Kind.PROGRESSIVE, UrlSniffer.sniff("https://cdn.example.com/a.mkv")?.kind)
    }

    @Test
    fun hlsAndDash() {
        assertEquals(UrlSniffer.Kind.HLS, UrlSniffer.sniff("https://cdn.example.com/master.m3u8")?.kind)
        assertEquals(UrlSniffer.Kind.HLS, UrlSniffer.sniff("https://cdn.example.com/hls/index.m3u8?hd=1")?.kind)
        assertEquals(UrlSniffer.Kind.DASH, UrlSniffer.sniff("https://cdn.example.com/manifest.mpd")?.kind)
        assertEquals(UrlSniffer.Kind.DASH, UrlSniffer.sniff("https://cdn.example.com/stream.mpd?x=1")?.kind)
    }

    @Test
    fun rejectsBlobAndSegments() {
        assertNull(UrlSniffer.sniff("blob:https://youtube.com/8c2d-1"))
        assertNull(UrlSniffer.sniff("data:video/mp4;base64,AAAA"))
        assertNull(UrlSniffer.sniff("https://cdn.example.com/seg/0.ts"))
        assertNull(UrlSniffer.sniff("https://cdn.example.com/init.m4s"))
        assertNull(UrlSniffer.sniff("https://cdn.example.com/file.mp4.js"))
    }

    @Test
    fun contentTypeAndGooglevideo() {
        assertEquals(
            UrlSniffer.Kind.HLS,
            UrlSniffer.sniff("https://cdn.example.com/playlist", "application/vnd.apple.mpegurl; charset=utf-8")?.kind,
        )
        assertEquals(
            UrlSniffer.Kind.DASH,
            UrlSniffer.sniff("https://cdn.example.com/manifest", "application/dash+xml")?.kind,
        )
        assertEquals(
            UrlSniffer.Kind.PROGRESSIVE,
            UrlSniffer.sniff("https://cdn.example.com/media", "video/webm")?.kind,
        )
        assertEquals(
            UrlSniffer.Kind.PROGRESSIVE,
            UrlSniffer.sniff("https://rr3.googlevideo.com/videoplayback?mime=video%2Fmp4&id=x")?.kind,
        )
        assertNull(UrlSniffer.sniff("https://cdn.example.com/photo.jpg", "image/jpeg"))
    }

    @Test
    fun guessHeightReadsCommonTokens() {
        assertEquals(1080, UrlSniffer.guessHeight("1080p", null))
        assertEquals(720, UrlSniffer.guessHeight(null, "https://cdn.example.com/v/720p/master.m3u8"))
        assertEquals(2160, UrlSniffer.guessHeight("2160", "https://cdn.example.com/a.mp4"))
        assertEquals(0, UrlSniffer.guessHeight("audio", "https://cdn.example.com/a.mp4"))
        assertEquals(0, UrlSniffer.guessHeight(null, "https://cdn.example.com/v1720.mp4"))
    }

    @Test
    fun addressBarResolvesSearchAndUrls() {
        assertEquals(AddressBar.HOME, AddressBar.resolve("  ", SearchEngine.DUCKDUCKGO))
        assertEquals("https://example.com/a", AddressBar.resolve("example.com/a", SearchEngine.DUCKDUCKGO))
        assertEquals("https://example.com:8080/a", AddressBar.resolve("example.com:8080/a", SearchEngine.DUCKDUCKGO))
        assertEquals("http://192.168.0.12/", AddressBar.resolve("192.168.0.12/", SearchEngine.DUCKDUCKGO))
        assertEquals("https://already.example/x", AddressBar.resolve("https://already.example/x", SearchEngine.GOOGLE))
        val search = AddressBar.resolve("что посмотреть", SearchEngine.DUCKDUCKGO)
        assertTrueStarts(search, "https://duckduckgo.com/?q=")
        assertEquals("yandex.ru", AddressBar.displayHost("https://www.yandex.ru/search"))
    }

    private fun assertTrueStarts(value: String, prefix: String) {
        org.junit.Assert.assertTrue("$value should start with $prefix", value.startsWith(prefix))
    }
}
