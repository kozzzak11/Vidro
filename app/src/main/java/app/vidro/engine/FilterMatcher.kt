package app.vidro.engine

/**
 * Fast enough ABP/uBlock-style network and cosmetic matcher.
 *
 * Plain `||domain^` rules live in a hash set. Everything else is indexed by
 * the request host, the document host, or a short literal token taken from
 * the pattern. Unsupported actions (removeparam, csp, redirect-rule, …) are
 * skipped so they cannot blank a page.
 */
class FilterMatcher private constructor(
    private val blockedDomains: Set<String>,
    private val allowedDomains: Set<String>,
    private val hostRules: Map<String, List<Rule>>,
    private val docRules: Map<String, List<Rule>>,
    private val tokenRules: Map<String, List<Rule>>,
    private val tokenless: List<Rule>,
    private val regexRules: List<Rule>,
    private val genericCosmetic: List<String>,
    private val genericCosmeticExceptions: Set<String>,
    private val cosmeticByDomain: Map<String, List<String>>,
    private val cosmeticExceptionsByDomain: Map<String, Set<String>>,
    val stats: Stats,
) {
    fun shouldBlock(request: Request): Boolean {
        val url = request.url
        if (url.length > 8000) return false
        val schemeEnd = url.indexOf(':')
        if (schemeEnd <= 0) return false
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = AddressBar.hostOf(url) ?: return false
        val documentHost = normalizeHost(request.documentHost)
        val thirdParty = documentHost != null && registrable(host) != registrable(documentHost)
        val urlLower = url.lowercase()

        var block = false
        var blockImportant = false
        var allow = false
        var allowImportant = false

        fun consider(rule: Rule) {
            if (!rule.applies(url, urlLower, host, documentHost, request.type, thirdParty)) return
            if (rule.exception) {
                if (rule.important) allowImportant = true else allow = true
            } else if (rule.important) {
                blockImportant = true
            } else {
                block = true
            }
        }

        var suffix = host
        while (suffix.isNotEmpty()) {
            if (suffix in blockedDomains) block = true
            if (suffix in allowedDomains) allow = true
            hostRules[suffix]?.forEach(::consider)
            val dot = suffix.indexOf('.')
            if (dot < 0) break
            suffix = suffix.substring(dot + 1)
        }

        var doc = documentHost.orEmpty()
        while (doc.isNotEmpty()) {
            docRules[doc]?.forEach(::consider)
            val dot = doc.indexOf('.')
            if (dot < 0) break
            doc = doc.substring(dot + 1)
        }

        if (allowImportant) return false

        if (tokenRules.isNotEmpty()) {
            val seen = HashSet<Rule>()
            for (key in windows(urlLower)) {
                val bucket = tokenRules[key] ?: continue
                for (rule in bucket) {
                    if (seen.add(rule)) consider(rule)
                }
            }
        }
        if (allowImportant) return false
        for (rule in tokenless) consider(rule)
        for (rule in regexRules) consider(rule)

        if (blockImportant && !allowImportant) return true
        if (allowImportant || allow) return false
        return block
    }

    /** Patterns that would block [request], ignoring exceptions. For tests and diagnostics. */
    fun blockingPatterns(request: Request): List<String> {
        val url = request.url
        val host = AddressBar.hostOf(url) ?: return emptyList()
        val documentHost = normalizeHost(request.documentHost)
        val thirdParty = documentHost != null && registrable(host) != registrable(documentHost)
        val urlLower = url.lowercase()
        val hits = LinkedHashSet<String>()
        fun consider(rule: Rule) {
            if (rule.exception) return
            if (!rule.applies(url, urlLower, host, documentHost, request.type, thirdParty)) return
            hits.add(rule.rawPattern)
        }
        var suffix = host
        while (suffix.isNotEmpty()) {
            if (suffix in blockedDomains) hits.add("||$suffix^")
            hostRules[suffix]?.forEach(::consider)
            val dot = suffix.indexOf('.')
            if (dot < 0) break
            suffix = suffix.substring(dot + 1)
        }
        var doc = documentHost.orEmpty()
        while (doc.isNotEmpty()) {
            docRules[doc]?.forEach(::consider)
            val dot = doc.indexOf('.')
            if (dot < 0) break
            doc = doc.substring(dot + 1)
        }
        val seen = HashSet<Rule>()
        for (key in windows(urlLower)) {
            val bucket = tokenRules[key] ?: continue
            for (rule in bucket) if (seen.add(rule)) consider(rule)
        }
        for (rule in tokenless) consider(rule)
        for (rule in regexRules) consider(rule)
        return hits.toList()
    }

    fun cosmeticChunks(host: String?): List<String> {
        val normalized = normalizeHost(host).orEmpty()
        val excluded = HashSet<String>(genericCosmeticExceptions.size + 8)
        excluded.addAll(genericCosmeticExceptions)
        val specific = ArrayList<String>()
        if (normalized.isNotEmpty()) {
            var suffix = normalized
            while (suffix.isNotEmpty()) {
                val keyed = suffix.contains('.') || suffix == normalized
                if (keyed) {
                    cosmeticExceptionsByDomain[suffix]?.let { excluded.addAll(it) }
                    cosmeticByDomain[suffix]?.let { specific.addAll(it) }
                }
                val dot = suffix.indexOf('.')
                if (dot < 0) break
                suffix = suffix.substring(dot + 1)
            }
        }
        if (genericCosmetic.isEmpty() && specific.isEmpty()) return emptyList()
        val selectors = LinkedHashSet<String>(minOf(genericCosmetic.size + specific.size, 12_000))
        for (selector in genericCosmetic) {
            if (selector !in excluded) selectors.add(selector)
        }
        for (selector in specific) {
            if (selector !in excluded) selectors.add(selector)
        }
        if (selectors.isEmpty()) return emptyList()
        return chunkSelectors(selectors)
    }

    private class Rule(
        val exception: Boolean,
        val important: Boolean,
        val party: Party,
        val types: TypeMask,
        val domains: DomainMask,
        val anchorDomain: String?,
        val hostOnly: Boolean,
        val matchAll: Boolean,
        val plain: String?,
        val regexBody: String?,
        val regexSensitive: Boolean,
        val rawPattern: String,
    ) {
        @Volatile private var compiled: Regex? = null
        @Volatile private var failed: Boolean = false

        fun applies(
            url: String,
            urlLower: String,
            host: String,
            documentHost: String?,
            type: ResourceType,
            thirdParty: Boolean,
        ): Boolean {
            if (!types.allows(type)) return false
            if (!party.allows(thirdParty)) return false
            if (!domains.allows(documentHost)) return false
            val anchor = anchorDomain
            if (anchor != null && host != anchor && !host.endsWith(".$anchor")) return false
            if (hostOnly || matchAll) return true
            if (plain != null) return plain in urlLower
            val regex = regex() ?: return false
            return regex.containsMatchIn(if (regexSensitive) url else urlLower)
        }

        private fun regex(): Regex? {
            compiled?.let { return it }
            if (failed) return null
            return synchronized(this) {
                compiled ?: try {
                    val built = if (regexBody != null) {
                        if (regexTooDangerous(regexBody)) {
                            failed = true
                            return null
                        }
                        val options = if (regexSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                        Regex(regexBody, options)
                    } else {
                        abpToRegex(rawPattern)
                    }
                    compiled = built
                    built
                } catch (_: Throwable) {
                    failed = true
                    null
                }
            }
        }
    }

    data class Stats(
        val blockedDomains: Int,
        val allowedDomains: Int,
        val networkRules: Int,
        val cosmeticRules: Int,
        val tokenless: Int,
    )

    enum class Party {
        ANY, THIRD, FIRST;

        fun allows(thirdParty: Boolean): Boolean = when (this) {
            ANY -> true
            THIRD -> thirdParty
            FIRST -> !thirdParty
        }
    }

    private class TypeMask(val includes: Set<ResourceType>?, val excludes: Set<ResourceType>) {
        fun allows(type: ResourceType): Boolean {
            if (type in excludes) return false
            val inc = includes ?: return true
            return type in inc
        }
    }

    private class DomainMask(val include: Set<String>, val exclude: Set<String>) {
        fun allows(documentHost: String?): Boolean {
            if (documentHost != null && exclude.any { domainMatches(documentHost, it) }) return false
            if (include.isEmpty()) return true
            if (documentHost == null) return false
            return include.any { domainMatches(documentHost, it) }
        }
    }

    companion object {
        private val simpleDomain = Regex("""^\|\|([a-z0-9-]+(?:\.[a-z0-9-]+)+)\^$""", RegexOption.IGNORE_CASE)
        private val runRegex = Regex("[a-z0-9%]{4,}")
        private val stopTokens = setOf(
            "www", "com", "net", "org", "html", "php", "http", "https", "index", "static",
            "cdn", "img", "jpg", "jpeg", "png", "gif", "css", "json", "xml", "aspx",
            "html", "true", "false", "null", "page", "home", "file", "data",
        )
        private val proceduralMarkers = listOf(
            ":remove", ":style(", ":has(", ":has-text", ":matches-", ":xpath", ":upward",
            ":min-text", ":watch-", ":others", ":-abp-", "+js(", ":matches-css",
        )
        private val dangerousSelectors = setOf(
            "video", "body", "html", "head", "script", "iframe", "#movie_player",
            "#player", ".html5-video-player", "ytd-app", "#content", "#page",
        )
        private val skipOptions = setOf(
            "badfilter", "generichide", "genericblock", "elemhide", "ehide", "ghide",
            "specifichide", "shide", "specificehide", "removeparam", "csp", "replace",
            "redirect-rule", "urltransform", "uritransform", "permissions", "header",
            "method", "to", "from", "denyallow", "empty", "urlskip", "rewrite", "webrtc",
            "inline-script", "inline-font", "ipaddress", "jsonprune", "hls", "referrerpolicy",
        )

        fun empty(): FilterMatcher = compile(emptyList())

        fun compile(lines: Iterable<String>): FilterMatcher {
            val blocked = HashSet<String>(16_384)
            val allowed = HashSet<String>(1024)
            val hostRules = HashMap<String, MutableList<Rule>>()
            val docRules = HashMap<String, MutableList<Rule>>()
            val tokenRules = HashMap<String, MutableList<Rule>>()
            val tokenless = ArrayList<Rule>()
            val regexRules = ArrayList<Rule>()
            val generic = ArrayList<String>(4096)
            val genericExc = HashSet<String>()
            val byDomain = HashMap<String, MutableList<String>>()
            val excByDomain = HashMap<String, MutableSet<String>>()
            var network = 0
            var cosmetic = 0

            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '!' || line[0] == '[') continue
                if (line.length > 2000) continue
                if (consumeCosmetic(line, generic, genericExc, byDomain, excByDomain)) {
                    cosmetic++
                    continue
                }
                if (addNetwork(line, blocked, allowed, hostRules, docRules, tokenRules, tokenless, regexRules)) {
                    network++
                }
            }

            return FilterMatcher(
                blockedDomains = blocked,
                allowedDomains = allowed,
                hostRules = hostRules,
                docRules = docRules,
                tokenRules = tokenRules,
                tokenless = tokenless,
                regexRules = regexRules,
                genericCosmetic = generic,
                genericCosmeticExceptions = genericExc,
                cosmeticByDomain = byDomain,
                cosmeticExceptionsByDomain = excByDomain,
                stats = Stats(
                    blockedDomains = blocked.size,
                    allowedDomains = allowed.size,
                    networkRules = network,
                    cosmeticRules = cosmetic,
                    tokenless = tokenless.size,
                ),
            )
        }

        fun inferType(url: String, isMainFrame: Boolean): ResourceType {
            if (isMainFrame) return ResourceType.DOCUMENT
            return when (extensionOf(url)) {
                "js", "mjs" -> ResourceType.SCRIPT
                "css" -> ResourceType.STYLESHEET
                "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp" -> ResourceType.IMAGE
                "woff", "woff2", "ttf", "otf", "eot" -> ResourceType.FONT
                "mp4", "webm", "m4v", "mkv", "mov", "m3u8", "mpd", "mp3", "aac", "ogg", "ts", "m4s" ->
                    ResourceType.MEDIA
                "html", "htm", "php", "asp", "aspx" -> ResourceType.SUBDOCUMENT
                else -> ResourceType.OTHER
            }
        }

        fun estimatedBytes(type: ResourceType): Long = when (type) {
            ResourceType.SCRIPT -> 35_000
            ResourceType.IMAGE -> 45_000
            ResourceType.STYLESHEET -> 18_000
            ResourceType.FONT -> 25_000
            ResourceType.MEDIA -> 180_000
            ResourceType.SUBDOCUMENT -> 30_000
            ResourceType.XMLHTTPREQUEST -> 8_000
            ResourceType.DOCUMENT -> 40_000
            ResourceType.POPUP -> 20_000
            else -> 12_000
        }

        private fun addNetwork(
            line: String,
            blocked: MutableSet<String>,
            allowed: MutableSet<String>,
            hostRules: MutableMap<String, MutableList<Rule>>,
            docRules: MutableMap<String, MutableList<Rule>>,
            tokenRules: MutableMap<String, MutableList<Rule>>,
            tokenless: MutableList<Rule>,
            regexRules: MutableList<Rule>,
        ): Boolean {
            var body = line
            val exception = body.startsWith("@@")
            if (exception) body = body.substring(2)
            if (body.isEmpty() || body.any { it.isWhitespace() }) return false
            val (pattern, optionsText) = splitOptions(body)
            if (pattern.isEmpty() || pattern.length > 1000) return false
            val options = parseOptions(optionsText) ?: return false

            val simple = simpleDomain.matchEntire(pattern)
            if (simple != null && options.isBare()) {
                val domain = simple.groupValues[1].lowercase()
                if (exception) allowed.add(domain) else blocked.add(domain)
                return true
            }

            val regexDelimited = regexParts(pattern)
            val anchor = if (regexDelimited == null) anchorDomain(pattern) else null
            val normalizedPattern = pattern.lowercase()
            val hostOnly = anchor != null &&
                !pattern.contains('*') &&
                !pattern.contains('/') &&
                pattern.count { it == '^' } <= 1 &&
                (normalizedPattern == "||$anchor^" || normalizedPattern == "||$anchor^|")
            val matchAll = pattern == "*" || pattern == "^" || pattern == "^*" || pattern == "*^"
            if (
                matchAll && !exception && options.party == Party.ANY &&
                options.types.includes == null && options.types.excludes.isEmpty() &&
                options.domains.include.isEmpty()
            ) {
                return false
            }
            val plain = if (
                regexDelimited == null && anchor == null && !matchAll &&
                pattern.none { it == '*' || it == '^' || it == '|' }
            ) {
                pattern.lowercase()
            } else {
                null
            }

            val rule = Rule(
                exception = exception,
                important = options.important,
                party = options.party,
                types = options.types,
                domains = options.domains,
                anchorDomain = anchor,
                hostOnly = hostOnly && anchor != null,
                matchAll = matchAll,
                plain = plain,
                regexBody = regexDelimited?.first,
                regexSensitive = regexDelimited?.second == true || options.matchCase,
                rawPattern = pattern,
            )

            when {
                anchor != null -> hostRules.getOrPut(anchor) { ArrayList() }.add(rule)
                regexDelimited != null -> regexRules.add(rule)
                options.domains.include.isNotEmpty() && options.domains.include.none { '*' in it } -> {
                    for (domain in options.domains.include) {
                        docRules.getOrPut(domain) { ArrayList() }.add(rule)
                    }
                }
                else -> {
                    val key = indexKey(pattern)
                    if (key == null) tokenless.add(rule) else tokenRules.getOrPut(key) { ArrayList() }.add(rule)
                }
            }
            return true
        }

        private fun consumeCosmetic(
            line: String,
            generic: MutableList<String>,
            genericExc: MutableSet<String>,
            byDomain: MutableMap<String, MutableList<String>>,
            excByDomain: MutableMap<String, MutableSet<String>>,
        ): Boolean {
            if ("#?#" in line || "#$#" in line || "#%#" in line) return true
            val exception = "#@#" in line
            if (!exception && "##" !in line) return false
            val marker = if (exception) "#@#" else "##"
            val index = line.indexOf(marker)
            if (index < 0) return false
            val left = line.substring(0, index).trim()
            val selector = line.substring(index + marker.length).trim()
            if (selector.isEmpty() || !selectorOk(selector)) return true
            val positive = ArrayList<String>(2)
            val negative = ArrayList<String>(1)
            if (left.isNotEmpty()) {
                for (part in left.split(',')) {
                    var domain = part.trim().lowercase()
                    if (domain.isEmpty()) continue
                    val neg = domain.startsWith('~')
                    if (neg) domain = domain.substring(1)
                    domain = domain.trim('.')
                    if (domain.isEmpty()) continue
                    if (neg) negative.add(domain) else positive.add(domain)
                }
            }
            if (exception) {
                if (positive.isEmpty()) genericExc.add(selector)
                else positive.forEach { excByDomain.getOrPut(it) { HashSet() }.add(selector) }
                return true
            }
            if (positive.isEmpty()) {
                generic.add(selector)
            } else {
                positive.forEach { byDomain.getOrPut(it) { ArrayList() }.add(selector) }
            }
            negative.forEach { excByDomain.getOrPut(it) { HashSet() }.add(selector) }
            return true
        }

        private fun selectorOk(selector: String): Boolean {
            if (selector.length > 280) return false
            if ('{' in selector || '}' in selector || '<' in selector || '>' in selector) return false
            if (selector in dangerousSelectors) return false
            return proceduralMarkers.none { it in selector }
        }

        private data class ParsedOptions(
            val important: Boolean,
            val party: Party,
            val types: TypeMask,
            val domains: DomainMask,
            val matchCase: Boolean,
        ) {
            fun isBare(): Boolean =
                !important && party == Party.ANY && types.includes == null && types.excludes.isEmpty() &&
                    domains.include.isEmpty() && domains.exclude.isEmpty() && !matchCase
        }

        private fun parseOptions(text: String?): ParsedOptions? {
            if (text.isNullOrEmpty()) {
                return ParsedOptions(false, Party.ANY, TypeMask(null, emptySet()), DomainMask(emptySet(), emptySet()), false)
            }
            val includes = HashSet<ResourceType>()
            val excludes = HashSet<ResourceType>()
            var sawPositive = false
            var important = false
            var party = Party.ANY
            var matchCase = false
            val includeDomains = HashSet<String>()
            val excludeDomains = HashSet<String>()
            for (raw in text.split(',')) {
                val part = raw.trim()
                if (part.isEmpty()) continue
                val keyRaw = part.substringBefore('=').trim()
                val value = if ('=' in part) part.substringAfter('=') else null
                val negated = keyRaw.startsWith('~')
                val key = keyRaw.removePrefix("~").lowercase()
                when (key) {
                    "important" -> important = true
                    "match-case" -> matchCase = true
                    "all", "redirect", "cname", "reason" -> Unit
                    in skipOptions -> return null
                    "third-party", "3p", "strict3p" -> party = if (negated) Party.FIRST else Party.THIRD
                    "first-party", "1p", "strict1p" -> party = if (negated) Party.THIRD else Party.FIRST
                    "domain" -> {
                        if (value.isNullOrEmpty()) return null
                        for (item in value.split('|')) {
                            var domain = item.trim().lowercase()
                            if (domain.isEmpty()) continue
                            val neg = domain.startsWith('~')
                            if (neg) domain = domain.substring(1)
                            domain = domain.trim('.')
                            if (domain.isEmpty()) continue
                            if (neg) excludeDomains.add(domain) else includeDomains.add(domain)
                        }
                    }
                    else -> {
                        val type = typeOf(key) ?: return null
                        if (negated) excludes.add(type) else {
                            includes.add(type)
                            sawPositive = true
                        }
                    }
                }
            }
            return ParsedOptions(
                important = important,
                party = party,
                types = TypeMask(if (sawPositive) includes else null, excludes),
                domains = DomainMask(includeDomains, excludeDomains),
                matchCase = matchCase,
            )
        }

        private fun typeOf(key: String): ResourceType? = when (key) {
            "script" -> ResourceType.SCRIPT
            "image" -> ResourceType.IMAGE
            "stylesheet", "css" -> ResourceType.STYLESHEET
            "xmlhttprequest", "xhr" -> ResourceType.XMLHTTPREQUEST
            "subdocument", "frame" -> ResourceType.SUBDOCUMENT
            "document", "doc" -> ResourceType.DOCUMENT
            "media", "mp4" -> ResourceType.MEDIA
            "font" -> ResourceType.FONT
            "other" -> ResourceType.OTHER
            "websocket" -> ResourceType.WEBSOCKET
            "ping" -> ResourceType.PING
            "popup", "popunder" -> ResourceType.POPUP
            "object", "object-subrequest" -> ResourceType.OBJECT
            else -> null
        }

        private fun splitOptions(body: String): Pair<String, String?> {
            if (body.startsWith("/")) {
                var i = 1
                while (i < body.length) {
                    if (body[i] == '\\') {
                        i += 2
                        continue
                    }
                    if (body[i] == '/') break
                    i++
                }
                if (i < body.length && body[i] == '/') {
                    val after = body.substring(i + 1)
                    val dollar = after.indexOf('$')
                    return if (dollar >= 0) {
                        body.substring(0, i + 1) + after.substring(0, dollar) to after.substring(dollar + 1)
                    } else {
                        body to null
                    }
                }
            }
            val index = body.lastIndexOf('$')
            if (index > 0) {
                val opt = body.substring(index + 1)
                val keysOk = opt.isNotEmpty() && opt.split(',').all { part ->
                    val key = part.substringBefore('=').removePrefix("~").trim()
                    key.isNotEmpty() && key.length <= 40 && key.all { it.isLetterOrDigit() || it == '-' || it == '_' }
                }
                if (keysOk) return body.substring(0, index) to opt
            }
            return body to null
        }

        /** @return regex body and whether it is case-sensitive */
        private fun regexParts(pattern: String): Pair<String, Boolean>? {
            if (pattern.length < 2 || !pattern.startsWith("/")) return null
            var i = 1
            while (i < pattern.length) {
                if (pattern[i] == '\\') {
                    i += 2
                    continue
                }
                if (pattern[i] == '/') break
                i++
            }
            if (i >= pattern.length || pattern[i] != '/') return null
            val flags = pattern.substring(i + 1)
            // ABP/uBlock regex filters only allow an optional "i" flag.
            // Paths such as /amp/analytics must stay literal, not /amp/ + flags.
            if (flags.any { it != 'i' }) return null
            val body = pattern.substring(1, i)
            if (body.isEmpty()) return null
            val sensitive = 'i' !in flags.lowercase()
            return body to sensitive
        }

        private fun anchorDomain(pattern: String): String? {
            if (!pattern.startsWith("||")) return null
            val body = pattern.substring(2)
            val sb = StringBuilder()
            for (ch in body) {
                if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') sb.append(ch) else break
            }
            val domain = sb.toString().lowercase().trim('.')
            if (!domain.contains('.')) return null
            return domain
        }

        private fun indexKey(pattern: String): String? {
            val runs = runRegex.findAll(pattern.lowercase()).map { it.value }.toList()
            val token = runs
                .filter { it !in stopTokens && it.take(6) !in stopTokens }
                .maxByOrNull { it.length }
                ?: return null
            return if (token.length >= 6) token.substring(0, 6) else token
        }

        private fun windows(urlLower: String): Set<String> {
            val out = HashSet<String>()
            for (match in runRegex.findAll(urlLower)) {
                val token = match.value
                val limit = minOf(token.length, 48)
                if (token.length <= 5) {
                    out.add(token)
                    continue
                }
                for (len in 4..6) {
                    var i = 0
                    while (i + len <= limit) {
                        out.add(token.substring(i, i + len))
                        i++
                    }
                }
            }
            return out
        }

        private fun domainMatches(host: String, ruleDomain: String): Boolean {
            val left = host.lowercase()
            val right = ruleDomain.lowercase()
            if ('*' !in right) return left == right || left.endsWith(".$right")
            val body = right.replace(".", "\\.").replace("*", "[^.]*")
            return Regex("(?:^|\\.)$body$").containsMatchIn(left)
        }

        private fun extensionOf(url: String): String {
            val path = url.substringBefore('?').substringBefore('#')
            val file = path.substringAfterLast('/')
            val dot = file.lastIndexOf('.')
            if (dot < 0 || dot == file.lastIndex) return ""
            return file.substring(dot + 1).lowercase()
        }

        private fun regexTooDangerous(body: String): Boolean {
            if (body.length > 180) return true
            if ("(?=" in body || "(?!" in body || "(?<" in body) return true
            return Regex("""\{\d{2,}""").containsMatchIn(body)
        }

        private fun abpToRegex(pattern: String): Regex {
            var rest = pattern
            val sb = StringBuilder()
            if (rest.startsWith("||")) {
                sb.append("^(?:[a-zA-Z][a-zA-Z0-9+.-]*:)?//(?:[^/?#]*\\.)?")
                rest = rest.substring(2)
            } else if (rest.startsWith("|")) {
                sb.append('^')
                rest = rest.substring(1)
            }
            var endAnchor = false
            if (rest.endsWith("|") && !rest.endsWith("||")) {
                endAnchor = true
                rest = rest.dropLast(1)
            }
            for (ch in rest) {
                when (ch) {
                    '*' -> sb.append(".*")
                    '^' -> sb.append("(?:[^A-Za-z0-9_%.-]|$)")
                    else -> {
                        if (ch in "\\.[]{}()+?\$|") sb.append('\\')
                        sb.append(ch)
                    }
                }
            }
            if (endAnchor) sb.append('$')
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }

        private fun chunkSelectors(selectors: Collection<String>): List<String> {
            val chunks = ArrayList<String>()
            val current = StringBuilder()
            var count = 0
            var budget = 350_000
            for (selector in selectors) {
                if (budget <= 0) break
                if (count >= 1500) {
                    current.append("{display:none!important}")
                    chunks.add(current.toString())
                    current.clear()
                    count = 0
                }
                if (count > 0) current.append(',')
                current.append(selector)
                budget -= selector.length + 1
                count++
            }
            if (count > 0) {
                current.append("{display:none!important}")
                chunks.add(current.toString())
            }
            return chunks
        }

        private fun normalizeHost(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val trimmed = value.trim()
            val host = if ("://" in trimmed) AddressBar.hostOf(trimmed) else {
                trimmed.substringBefore('/').substringBefore(':').trim('.').lowercase()
            }
            return host?.takeIf { it.isNotEmpty() }
        }

        private fun registrable(host: String): String {
            val parts = host.lowercase().split('.').filter { it.isNotEmpty() }
            if (parts.size <= 2) return parts.joinToString(".")
            val sld = parts[parts.size - 2]
            val tld = parts[parts.size - 1]
            val multi = tld.length == 2 && sld in setOf("co", "com", "org", "net", "ac", "gov", "edu", "or", "ne", "go")
            val take = if (multi) 3 else 2
            return parts.takeLast(take).joinToString(".")
        }
    }
}

enum class ResourceType {
    DOCUMENT,
    SCRIPT,
    IMAGE,
    STYLESHEET,
    SUBDOCUMENT,
    XMLHTTPREQUEST,
    MEDIA,
    FONT,
    WEBSOCKET,
    OTHER,
    POPUP,
    OBJECT,
    PING,
}

data class Request(
    val url: String,
    val documentHost: String?,
    val type: ResourceType,
    val isPopup: Boolean = false,
)
