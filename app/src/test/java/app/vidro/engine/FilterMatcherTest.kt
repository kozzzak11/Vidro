package app.vidro.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilterMatcherTest {
    @Test
    fun blocksAnchoredDomainAndSubdomains() {
        val matcher = FilterMatcher.compile(listOf("||ads.test^"))
        assertTrue(matcher.shouldBlock(req("https://ads.test/banner.js", "news.example", ResourceType.SCRIPT)))
        assertTrue(matcher.shouldBlock(req("https://a.ads.test/x", "news.example", ResourceType.IMAGE)))
        assertFalse(matcher.shouldBlock(req("https://notads.test/x", "news.example", ResourceType.SCRIPT)))
        assertFalse(matcher.shouldBlock(req("https://ads.test.evil.example/x", "news.example", ResourceType.SCRIPT)))
    }

    @Test
    fun exceptionOverridesBlockUnlessImportant() {
        val matcher = FilterMatcher.compile(
            listOf(
                "||ads.test^",
                "@@||safe.ads.test^",
            ),
        )
        assertTrue(matcher.shouldBlock(req("https://ads.test/x", "a.com", ResourceType.OTHER)))
        assertFalse(matcher.shouldBlock(req("https://safe.ads.test/x", "a.com", ResourceType.OTHER)))
        assertTrue(matcher.shouldBlock(req("https://evil.ads.test/x", "a.com", ResourceType.OTHER)))

        val important = FilterMatcher.compile(
            listOf(
                "||ads.test^\$important",
                "@@||ads.test^",
            ),
        )
        assertTrue(important.shouldBlock(req("https://ads.test/x", "a.com", ResourceType.OTHER)))
    }

    @Test
    fun pathAndWildcard() {
        val matcher = FilterMatcher.compile(
            listOf(
                "||cdn.test/ads/*",
                "*banner*",
            ),
        )
        assertTrue(matcher.shouldBlock(req("https://cdn.test/ads/banner.js", "a.com", ResourceType.SCRIPT)))
        assertFalse(matcher.shouldBlock(req("https://cdn.test/video/a.mp4", "a.com", ResourceType.MEDIA)))
        assertTrue(matcher.shouldBlock(req("https://static.example/img/banner-1.png", "a.com", ResourceType.IMAGE)))
    }

    @Test
    fun thirdPartyAndDomainOptionAndType() {
        val third = FilterMatcher.compile(listOf("||tracker.test^\$third-party"))
        assertTrue(third.shouldBlock(req("https://tracker.test/a", "news.example", ResourceType.SCRIPT)))
        assertFalse(third.shouldBlock(req("https://tracker.test/a", "www.tracker.test", ResourceType.SCRIPT)))
        assertFalse(third.shouldBlock(req("https://www.tracker.test/a", "sub.tracker.test", ResourceType.SCRIPT)))

        val domain = FilterMatcher.compile(listOf("||ads.test^\$domain=news.example"))
        assertTrue(domain.shouldBlock(req("https://ads.test/x", "news.example", ResourceType.SCRIPT)))
        assertFalse(domain.shouldBlock(req("https://ads.test/x", "other.example", ResourceType.SCRIPT)))

        val typed = FilterMatcher.compile(listOf("||ads.test^\$script"))
        assertTrue(typed.shouldBlock(req("https://ads.test/a.js", "news.example", ResourceType.SCRIPT)))
        assertFalse(typed.shouldBlock(req("https://ads.test/a.png", "news.example", ResourceType.IMAGE)))
    }

    @Test
    fun skipsNonBlockingOptionsAndComments() {
        val matcher = FilterMatcher.compile(
            listOf(
                "! ||evil.test^",
                "[Adblock Plus 2.0]",
                "||example.com^\$removeparam=utm_source",
                "||popup.test^\$popup",
                "/ad-pixel.",
            ),
        )
        assertFalse(matcher.shouldBlock(req("https://example.com/?utm_source=1", "a.com", ResourceType.DOCUMENT)))
        assertFalse(matcher.shouldBlock(req("https://evil.test/x", "a.com", ResourceType.SCRIPT)))
        assertFalse(matcher.shouldBlock(req("https://popup.test/ad", "a.com", ResourceType.DOCUMENT)))
        assertTrue(matcher.shouldBlock(req("https://popup.test/ad", "a.com", ResourceType.POPUP)))
        assertTrue(matcher.shouldBlock(req("https://cdn.test/ad-pixel.gif", "a.com", ResourceType.IMAGE)))
    }

    @Test
    fun cosmeticSelectorsRespectDomainAndExceptions() {
        val matcher = FilterMatcher.compile(
            listOf(
                "example.com##.ad-banner",
                "##.popup-ad",
                "example.com#@#.popup-ad",
                "other.com##.ad-banner",
                "foo.com##.bad:remove-attr(onclick)",
            ),
        )
        val example = matcher.cosmeticChunks("www.example.com").joinToString("\n")
        assertTrue(example.contains(".ad-banner"))
        assertFalse(example.contains(".popup-ad"))
        assertFalse(example.contains("remove-attr"))
        val other = matcher.cosmeticChunks("other.com").joinToString("\n")
        assertTrue(other.contains(".ad-banner"))
        assertTrue(other.contains(".popup-ad"))
    }

    @Test
    fun bundledListsBlockKnownTrackers() {
        val dir = File("src/main/assets/filters")
        assertTrue(dir.isDirectory)
        val lines = dir.listFiles { file -> file.extension == "txt" && file.name != "NOTICE.txt" }!!
            .flatMap { it.readLines() }
        val started = System.nanoTime()
        val matcher = FilterMatcher.compile(lines)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("compile took ${millis}ms stats=${matcher.stats}", millis < 20_000)
        assertTrue(matcher.stats.blockedDomains > 1000)
        assertTrue(matcher.shouldBlock(req("https://ad.doubleclick.net/ddm/ad", "example.com", ResourceType.IMAGE)))
        assertTrue(
            matcher.shouldBlock(
                req("https://www.google-analytics.com/analytics.js", "example.com", ResourceType.SCRIPT),
            ),
        )
        val home = req("https://example.com/", "example.com", ResourceType.DOCUMENT)
        assertFalse(matcher.blockingPatterns(home).joinToString(" | "), matcher.shouldBlock(home))
        println("bundled compile ${millis}ms $matcher.stats".replace("matcher.stats", matcher.stats.toString()))
    }

    private fun req(url: String, document: String, type: ResourceType) = Request(url, document, type)
}
