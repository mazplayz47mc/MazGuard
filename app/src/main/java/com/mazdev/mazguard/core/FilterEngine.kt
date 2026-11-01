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

    @Volatile var safeSearch: Boolean = true
    @Volatile var keywordFilter: Boolean = true

    @Volatile private var ready: Boolean = false
    private val readyLatch = java.util.concurrent.CountDownLatch(1)

    val count: Int get() = domains.size

    private val KEYWORDS = arrayOf(
        "porn", "porno", "xxx", "xvideos", "xnxx", "xhamster", "pornhub", "redtube",
        "youporn", "spankbang", "beeg", "txxx", "tnaflix", "motherless", "efukt",
        "hclips", "hqporner", "daftsex", "erome", "hentai", "nhentai", "ehentai",
        "rule34", "e621", "gelbooru", "danbooru", "sankaku", "fakku", "literotica",
        "chaturbate", "bongacams", "stripchat", "livejasmin", "cam4", "myfreecams",
        "camgirl", "sexcam", "sexvid", "xmovies", "tubegalore", "onlyfans", "fansly",
        "nsfw", "milf", "blowjob", "cumshot", "creampie", "bukkake", "gangbang",
        "incest", "lolicon", "lolita", "jerkoff", "fapping", "nude", "nudes",
        "escort", "brothel", "brazzers", "realitykings", "naughtyamerica", "bangbros",
        "adultfriendfinder", "ashleymadison", "sexting", "webcamsex", "desiporn",
        "indianporn", "xxxvideo", "sexvideo", "javhd", "javbus", "sukebei"
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
        safeSearch = prefs.safeSearch
        keywordFilter = prefs.keywordFilter
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
                for (k in KEYWORDS) {
                    if (label == k) return true
                    if (k.length >= 4 && label.startsWith(k)) return true
                    if (k.length >= 6 && label.endsWith(k)) return true
                }
            }
            start = end + 1
        }
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
