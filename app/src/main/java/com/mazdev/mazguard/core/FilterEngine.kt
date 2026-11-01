package com.mazdev.mazguard.core

/**
 * Domain matching engine. Runs on the DNS hot path, so every lookup must stay
 * allocation-light: a handful of hash lookups per query.
 *
 * The blocklist is a set of registrable/parent domains. A query for
 * "cdn.assets.example.com" is blocked when any suffix ("cdn.assets.example.com",
 * "assets.example.com", "example.com", "com") is present.
 */
object FilterEngine {

    @Volatile private var domains: Set<String> = emptySet()
    @Volatile private var allow: Set<String> = emptySet()
    @Volatile private var extra: Set<String> = emptySet()
    @Volatile private var siteKeywords: Set<String> = emptySet()

    @Volatile var safeSearch: Boolean = true
    @Volatile var keywordFilter: Boolean = true
    @Volatile var browserGate: Boolean = true

    @Volatile private var ready: Boolean = false
    private val readyLatch = java.util.concurrent.CountDownLatch(1)

    val count: Int get() = domains.size

    private val KEYWORDS = arrayOf(
        "porn", "porno", "xxx", "xvideos", "xnxx", "xhamster", "pornhub", "redtube",
        "youporn", "spankbang", "beeg", "txxx", "tnaflix", "motherless", "efukt",
        "hclips", "hqporner", "daftsex", "erome",
        // anime / manga porn and its "overflow" family: several of these do NOT
        // contain the word "hentai" (hanime.tv, doujins.com, hitomi.la, kemono...)
        "hentai", "nhentai", "ehentai", "imhentai", "hentaiverse",
        "hanime", "doujin", "ecchi", "kemono", "hitomi", "pururin", "tsumino",
        "multporn", "ahegao", "hcomic", "8muses", "luscious", "coomer",
        "rule34", "e621", "e926", "gelbooru", "danbooru", "sankaku", "fakku",
        "literotica",
        "chaturbate", "bongacams", "stripchat", "livejasmin", "cam4", "myfreecams",
        "camgirl", "sexcam", "sexvid", "xmovies", "tubegalore", "onlyfans", "fansly",
        "nsfw", "milf", "blowjob", "cumshot", "creampie", "bukkake", "gangbang",
        "incest", "lolicon", "shotacon", "lolita", "jerkoff", "fapping", "nude", "nudes",
        "escort", "brothel", "brazzers", "realitykings", "naughtyamerica", "bangbros",
        "adultfriendfinder", "ashleymadison", "sexting", "webcamsex", "desiporn",
        "indianporn", "xxxvideo", "sexvideo", "javhd", "javbus", "javlibrary",
        "javdoe", "sukebei",
        // added in 1.0.3 - more sites, anime-porn family, hookup/cam terms
        "eporner", "porndoe", "porntrex", "porntube", "pornone", "porn555", "porntn",
        "pronhub", "hotmovs", "upornia", "thothub", "fapello", "fap", "sex",
        "tits", "boobs", "pussy", "bigtits", "titties", "handjob", "titfuck",
        "shemale", "femdom", "fetish", "bdsm", "horny", "yiff",
        "missav", "thisav", "avmoo", "javfinder", "javfree", "javtiful", "njav",
        "tvjav", "javguru", "asianporn", "freeomovie", "xtheatre", "xtapes",
        "gayporn", "boyfriendtv", "thisvid", "camwhores", "camsoda", "jerkmate",
        "fapturbate", "flirt4free", "imlive", "backpage", "listcrawler", "slixa",
        "fling",
        "exhentai", "hentai2read", "hentaihaven", "hentaicloud", "hentaifox",
        "hentaigasm", "hentaiplay", "myhentai", "tanhentai", "9hentai",
        "toonporn", "cartoonporn", "porncomics", "jabcomix", "kirtu", "savita"
    )

    fun reload(context: android.content.Context, prefs: AppPrefs) {
        val set = HashSet<String>(1 shl 16)
        runCatching {
            context.assets.open("blocklist_seed.txt").bufferedReader().useLines { lines ->
                lines.forEach { line -> normalizeDomain(line)?.let { set.add(it) } }
            }
        }
        val downloaded = java.io.File(context.filesDir, "blocklist.txt")
        if (downloaded.exists()) {
            runCatching {
                downloaded.bufferedReader().useLines { lines ->
                    lines.forEach { line -> normalizeDomain(line)?.let { set.add(it) } }
                }
            }
        }
        domains = set
        refreshRules(prefs)
        ready = true
        readyLatch.countDown()
        prefs.listCount = set.size
    }

    /** The tunnel must not answer queries before the blocklist is in memory. */
    fun awaitReady(timeoutMs: Long) {
        if (ready) return
        runCatching { readyLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    fun refreshRules(prefs: AppPrefs) {
        allow = prefs.allowedDomains
        extra = prefs.blockedDomains
        siteKeywords = prefs.blockedKeywords.map { it.trim().lowercase() }.filter { it.length >= 3 }.toSet()
        safeSearch = prefs.safeSearch
        keywordFilter = prefs.keywordFilter
        browserGate = prefs.browserGate
    }

    fun isReady(): Boolean = ready

    fun isBlocked(rawHost: String): Boolean {
        val host = rawHost.lowercase().trimEnd('.')
        if (host.isEmpty() || host.length > 253) return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan")) return false

        if (matchesAny(extra, host)) return true
        if (matchesAny(allow, host)) return false
        if (matchesAny(domains, host)) return true
        if (keywordFilter && keywordHit(host)) return true
        return false
    }

    private fun matchesAny(set: Set<String>, host: String): Boolean {
        if (set.isEmpty()) return false
        if (set.contains(host)) return true
        var idx = host.indexOf('.')
        while (idx >= 0 && idx < host.length - 1) {
            if (set.contains(host.substring(idx + 1))) return true
            idx = host.indexOf('.', idx + 1)
        }
        return false
    }

    private fun keywordHit(host: String): Boolean {
        var start = 0
        while (start < host.length) {
            val end = host.indexOf('.', start).let { if (it < 0) host.length else it }
            val label = host.substring(start, end)
            if (label.length >= 3) {
                // Split on '-' so "watch-hentai-online" yields "hentai" as its
                // own token instead of failing prefix/suffix tests.
                var t = 0
                while (t < label.length) {
                    val u = label.indexOf('-', t).let { if (it < 0) label.length else it }
                    val token = label.substring(t, u)
                    if (token.length >= 3) {
                        for (k in KEYWORDS) {
                            if (token == k) return true
                            if (k.length >= 4 && token.startsWith(k)) return true
                            if (k.length >= 6 && token.endsWith(k)) return true
                        }
                    }
                    t = u + 1
                }
            }
            start = end + 1
        }
        return false
    }

    /**
     * Browser URL gate: decides whether a URL the screen shows should be
     * PIN-gated. Matches on token boundaries (URL split on anything that is
     * not a letter/digit) so the word "overflow" inside "stackoverflow" can
     * never trigger, but ".../watch/overflow-episode-1" does.
     *
     * Two lists are checked:
     *  - user-added keywords (Settings, e.g. "overflow")
     *  - a small built-in list of explicit-title terms that appear as URL words
     */
    fun urlHit(url: String): Boolean {
        val clean = url.lowercase().trim()
        if (clean.isEmpty() || clean.length > 500) return false
        // Skip pure-domain URLs: those are already handled by the DNS filter.
        var query = clean
        val schemeEnd = clean.indexOf("://")
        if (schemeEnd >= 0) query = clean.substring(schemeEnd + 3)
        val pathStart = query.indexOf('/')
        if (pathStart < 0) return false
        query = query.substring(pathStart)

        var t = 0
        while (t < query.length) {
            val ch = query[t]
            if (ch.isLetterOrDigit()) {
                var u = t
                while (u < query.length && query[u].isLetterOrDigit()) u++
                val token = query.substring(t, u)
                if (token.length >= 3 && tokenHit(token)) return true
                t = u
            } else {
                t++
            }
        }
        return false
    }

    private val URL_TERMS = arrayOf("hentai", "nhentai", "ehentai", "doujin", "ecchi", "ahegao", "hanime")

    private fun tokenHit(token: String): Boolean {
        if (tokenHitUrls(token, siteKeywords)) return true
        return tokenHitUrls(token, null)
    }

    private fun tokenHitUrls(token: String, custom: Set<String>?): Boolean {
        if (custom != null) {
            for (k in custom) {
                if (matchesToken(token, k)) return true
            }
            return false
        }
        for (k in URL_TERMS) {
            if (matchesToken(token, k)) return true
        }
        return false
    }

    private fun matchesToken(token: String, k: String): Boolean {
        if (token == k) return true
        if (k.length >= 5 && token.endsWith(k)) return true
        if (k.length >= 6 && token.startsWith(k)) return true
        return false
    }

    /** Turns hosts-file lines, adblock rules or bare domains into a clean domain. */
    fun normalizeDomain(rawLine: String): String? {
        var d = rawLine.trim()
        if (d.isEmpty()) return null
        if (d.startsWith("#") || d.startsWith("!") || d.startsWith("//")) return null
        if (d.startsWith("||")) {
            d = d.removePrefix("||").substringBefore('^').substringBefore('$')
        } else if (d.contains(' ') || d.contains('\t')) {
            d = d.split(' ', '\t').lastOrNull { it.isNotBlank() } ?: return null
        }
        d = d.substringBefore('#').substringBefore('$').trim()
        if (d.contains("://")) d = d.substringAfter("://")
        d = d.substringBefore('/').substringBefore(':').trim()
        d = d.trimEnd('.')
        d = d.removePrefix("*.").lowercase()
        if (d.isEmpty() || d.length > 253) return null
        if (d == "0.0.0.0" || d == "127.0.0.1" || d == "::1" || d == "::" || d == "localhost") return null
        if (!d.contains('.')) return null
        if (d.all { it.isDigit() || it == '.' }) return null
        if (!d.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
        return d
    }
}
